/**
 * autofill-engine.ts — Universal, site-agnostic autofill engine (content script).
 *
 * Architecture:
 *   RootRegistry        — forest of scannable roots (document, open ShadowRoots, same-origin iframes)
 *   HeuristicEngine     — weighted-regex field classifier + topological form-intent classifier
 *   MutationLifecycle   — dirty-root marking, trailing debounce + max-wait, idle-time rescans
 *   EventInterceptor    — capture-phase focusin/input/keydown/click/submit with composedPath() recovery
 *
 * No site-specific rules. Per-element state is WeakMap/WeakSet-keyed; per-root state
 * (observers, listeners, input caches) is explicitly released when a root is pruned.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

export type FieldRole =
  | 'username'
  | 'email'
  | 'current-password'
  | 'new-password'
  | 'confirm-password'
  | 'otp'
  | 'unknown';

export type FormIntent = 'login' | 'register' | 'password-update' | 'password-reset' | 'unknown';

export interface ClassifiedField {
  readonly element: HTMLInputElement;
  readonly role: FieldRole;
  /** 0..1 — 1.0 means authoritative (autocomplete token). */
  readonly confidence: number;
  readonly visible: boolean;
}

export interface ClassifiedForm {
  /** <form> when present, otherwise the smallest common container of the field cluster. */
  readonly container: HTMLElement;
  readonly intent: FormIntent;
  readonly confidence: number;
  readonly fields: readonly ClassifiedField[];
  readonly root: ScanRoot;
}

export interface Credential {
  readonly username?: string;
  readonly password?: string;
  readonly newPassword?: string;
}

export interface EngineEvents {
  onFormsChanged?(forms: readonly ClassifiedForm[]): void;
  onFieldFocused?(field: ClassifiedField, form: ClassifiedForm | null): void;
  onFormSubmitted?(form: ClassifiedForm, capturedValues: Readonly<Partial<Record<FieldRole, string>>>): void;
}

export interface EngineConfig {
  readonly debounceMs: number;
  readonly maxWaitMs: number;
  readonly minFieldScore: number;
  readonly maxIframeDepth: number;
  /** Minimum interval between synchronous rescans forced from event handlers. */
  readonly forcedRescanCooldownMs: number;
  /** Minimum interval between onFormSubmitted emissions for the same container. */
  readonly submitDedupeMs: number;
}

export type ScanRoot = Document | ShadowRoot;

const DEFAULT_CONFIG: EngineConfig = {
  debounceMs: 150,
  maxWaitMs: 1000,
  minFieldScore: 2,
  maxIframeDepth: 3,
  forcedRescanCooldownMs: 1000,
  submitDedupeMs: 500,
};

// ─────────────────────────────────────────────────────────────────────────────
// Realm-safe brand checks
//
// `instanceof HTMLInputElement` (etc.) fails for nodes owned by a same-origin
// iframe: those objects belong to the iframe's JS realm, whose constructors are
// different objects from ours. Every brand check therefore goes through
// nodeType/localName, which are realm-independent.
// ─────────────────────────────────────────────────────────────────────────────

function isElement(n: unknown): n is Element {
  return typeof n === 'object' && n !== null && (n as Node).nodeType === 1;
}

function isInput(n: unknown): n is HTMLInputElement {
  return isElement(n) && n.localName === 'input';
}

function isIframe(n: unknown): n is HTMLIFrameElement {
  return isElement(n) && n.localName === 'iframe';
}

function isFormElement(n: unknown): n is HTMLFormElement {
  return isElement(n) && n.localName === 'form';
}

function isDocumentRoot(root: ScanRoot): root is Document {
  return root.nodeType === 9; // Node.DOCUMENT_NODE
}

/**
 * A root is dead when its browsing context is gone (iframe removed/navigated →
 * defaultView === null) or, for shadow roots, when the host is detached or the
 * host's own document is dead.
 */
function isRootAlive(root: ScanRoot): boolean {
  try {
    if (isDocumentRoot(root)) return root.defaultView !== null;
    const host = root.host;
    return host.isConnected && host.ownerDocument.defaultView !== null;
  } catch {
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Heuristic signal tables (weighted regex — order-independent, site-agnostic)
// ─────────────────────────────────────────────────────────────────────────────

interface Signal {
  readonly re: RegExp;
  readonly weight: number;
}

const sig = (re: RegExp, weight: number): Signal => ({ re, weight });

/** Poison terms: any field matching these is heavily penalized for every role. */
const NEGATIVE: readonly Signal[] = [
  sig(/search|query|captcha|coupon|promo|gift.?card|zip|postal|city|street|company|subject|comment|message/i, -6),
  // "address" alone is poison (postal address) but must NOT match "email address",
  // one of the most common email-field labels on the web.
  sig(/(?<!e[-_ ]?mail[\s_-]{0,2})address/i, -6),
  sig(/card.?number|cvv|cvc|expir|iban|routing|account.?number/i, -6),
];

const SIGNALS: Readonly<Record<Exclude<FieldRole, 'unknown'>, readonly Signal[]>> = {
  username: [
    sig(/user.?name|login.?(id|name)?$|nick.?name|screen.?name|account.?(id|name)/i, 4),
    sig(/\b(user|login|identifier|handle|uid)\b/i, 3),
    sig(/member|customer.?(id|number)/i, 2),
  ],
  email: [
    sig(/e-?mail/i, 4),
    sig(/@/, 2), // placeholders like "name@example.com"
  ],
  'current-password': [
    sig(/current.?pass|old.?pass|existing.?pass|verify.?pass/i, 5),
    sig(/pass.?word|passwd|\bpwd\b|passphrase/i, 2),
  ],
  'new-password': [
    sig(/new.?pass|create.?pass|choose.?pass|set.?pass/i, 5),
    sig(/pass.?word|passwd|\bpwd\b|passphrase/i, 2),
  ],
  'confirm-password': [
    sig(/confirm|re.?(enter|type|peat)|again|verification|match/i, 4),
    sig(/pass.?word|passwd|\bpwd\b/i, 2),
  ],
  otp: [
    sig(/one.?time|otp|totp|2fa|mfa|verification.?code|security.?code|auth.?code|\bpin\b/i, 5),
    sig(/\bcode\b|token/i, 2),
  ],
};

/** Spec-defined autocomplete tokens — authoritative, bypass scoring entirely. */
const AUTOCOMPLETE_ROLES: Readonly<Record<string, FieldRole>> = {
  username: 'username',
  email: 'email',
  'current-password': 'current-password',
  'new-password': 'new-password',
  'one-time-code': 'otp',
};

/** Form-level text signals for intent tie-breaking (button labels, headings, form attrs). */
const INTENT_SIGNALS: Readonly<Record<Exclude<FormIntent, 'unknown'>, RegExp>> = {
  login: /log.?in|sign.?in|authenticate|welcome.?back/i,
  register: /register|sign.?up|create.?(an.?)?account|join|get.?started/i,
  'password-update': /change.?pass|update.?pass|edit.?pass/i,
  'password-reset': /reset|forgot|recover|restore/i,
};

// ─────────────────────────────────────────────────────────────────────────────
// DOM utilities
// ─────────────────────────────────────────────────────────────────────────────

function isVisible(el: HTMLElement): boolean {
  if (el.hidden || (el as HTMLInputElement).type === 'hidden') return false;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) return false;
  const style = getComputedStyle(el);
  return style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0';
}

function isFillable(input: HTMLInputElement): boolean {
  if (input.disabled || input.readOnly) return false;
  return /^(text|email|password|tel|number|)$/i.test(input.type);
}

/** Resolve human-readable label text: <label for>, wrapping label, aria-labelledby, aria-label. */
function resolveLabelText(input: HTMLInputElement): string {
  const parts: string[] = [];
  input.labels?.forEach((l) => parts.push(l.textContent ?? ''));
  const wrapping = input.closest('label');
  if (wrapping) parts.push(wrapping.textContent ?? '');
  const labelledBy = input.getAttribute('aria-labelledby');
  if (labelledBy) {
    const root = input.getRootNode() as ScanRoot;
    for (const id of labelledBy.split(/\s+/)) parts.push(root.getElementById?.(id)?.textContent ?? '');
  }
  parts.push(input.getAttribute('aria-label') ?? '');
  return parts.join(' ').replace(/\s+/g, ' ').trim().slice(0, 200);
}

/**
 * Concatenated attribute corpus the regex signals run against. className goes
 * last and is capped independently: utility-CSS class lists (Tailwind) can be
 * hundreds of characters and must not evict label/placeholder text from the
 * overall budget.
 */
function buildCorpus(input: HTMLInputElement): string {
  return [
    input.name,
    input.id,
    input.placeholder,
    input.getAttribute('data-testid') ?? '',
    resolveLabelText(input),
    input.className.slice(0, 120),
  ]
    .join(' ')
    .slice(0, 500);
}

/**
 * Set a value so framework-controlled inputs (React/Vue/Angular) register it:
 * use the native prototype setter to defeat value-tracking, then dispatch
 * composed input/change events. The setter must come from the element's OWN
 * realm — the top window's HTMLInputElement.prototype does not defeat the
 * value tracker of an input living in a same-origin iframe.
 */
export function setNativeValue(input: HTMLInputElement, value: string): void {
  const win = (input.ownerDocument.defaultView ?? window) as typeof globalThis;
  const setter = Object.getOwnPropertyDescriptor(win.HTMLInputElement.prototype, 'value')?.set;
  setter ? setter.call(input, value) : (input.value = value);
  for (const type of ['input', 'change'] as const) {
    input.dispatchEvent(new Event(type, { bubbles: true, composed: true }));
  }
}

/** Smallest common ancestor of a set of elements (fallback container for form-less clusters). */
function commonAncestor(elements: readonly HTMLElement[]): HTMLElement {
  let ancestor: HTMLElement = elements[0];
  for (let i = 1; i < elements.length; i++) {
    while (!ancestor.contains(elements[i])) {
      const parent = ancestor.parentElement ?? ((ancestor.getRootNode() as ShadowRoot).host as HTMLElement | undefined);
      if (!parent) return (ancestor.ownerDocument?.body ?? ancestor) as HTMLElement;
      ancestor = parent;
    }
  }
  return ancestor;
}

// ─────────────────────────────────────────────────────────────────────────────
// Heuristic Engine
// ─────────────────────────────────────────────────────────────────────────────

class HeuristicEngine {
  private readonly fieldCache = new WeakMap<HTMLInputElement, ClassifiedField>();
  /** Snapshot of everything the classification depends on; invalidates the cache when it drifts. */
  private readonly keyCache = new WeakMap<HTMLInputElement, string>();
  /**
   * Inputs that were ever type="password". Show/hide-password toggles flip the
   * type to "text"; without this memory the field would silently declassify.
   */
  private readonly everPassword = new WeakSet<HTMLInputElement>();

  constructor(private readonly config: EngineConfig) {}

  classifyField(input: HTMLInputElement): ClassifiedField {
    if (input.type === 'password') this.everPassword.add(input);
    const corpus = buildCorpus(input);
    const key = `${input.type}\u0000${input.getAttribute('autocomplete') ?? ''}\u0000${corpus}`;
    // Visibility is NEVER cached: it can change with zero attribute mutations on
    // the input itself (e.g. an ancestor modal toggling display).
    const visible = isVisible(input);

    const cached = this.fieldCache.get(input);
    if (cached && this.keyCache.get(input) === key) {
      if (cached.visible === visible) return cached;
      const updated: ClassifiedField = { ...cached, visible };
      this.fieldCache.set(input, updated);
      return updated;
    }
    const result = this.computeField(input, corpus, visible);
    this.fieldCache.set(input, result);
    this.keyCache.set(input, key);
    return result;
  }

  private computeField(input: HTMLInputElement, corpus: string, visible: boolean): ClassifiedField {
    // Tier 1: authoritative autocomplete token. Scan ALL tokens — markup like
    // "username webauthn" or "section-login current-password" carries the role
    // token in a non-terminal position.
    const tokens = (input.getAttribute('autocomplete') ?? '').trim().toLowerCase().split(/\s+/);
    for (const token of tokens) {
      const role = AUTOCOMPLETE_ROLES[token];
      if (role) return { element: input, role, confidence: 1, visible };
    }

    const isPassword = input.type === 'password' || this.everPassword.has(input);
    const roles: readonly FieldRole[] = isPassword
      ? ['current-password', 'new-password', 'confirm-password']
      : input.type === 'email'
        ? ['email']
        : ['username', 'email', 'otp'];

    let penalty = 0;
    for (const s of NEGATIVE) if (s.re.test(corpus)) penalty += s.weight;

    let bestRole: FieldRole = 'unknown';
    let bestScore = 0;
    for (const role of roles) {
      let score = penalty;
      for (const s of SIGNALS[role as Exclude<FieldRole, 'unknown'>]) if (s.re.test(corpus)) score += s.weight;
      if (score > bestScore) {
        bestScore = score;
        bestRole = role;
      }
    }

    // A bare password input with no markers is still a password — default to current-password;
    // ambiguity is resolved topologically at the form level.
    if (bestRole === 'unknown' && isPassword) {
      return { element: input, role: 'current-password', confidence: 0.4, visible };
    }
    // A bare email-typed input is an email even without name markers.
    if (bestRole === 'unknown' && input.type === 'email' && penalty === 0) {
      return { element: input, role: 'email', confidence: 0.6, visible };
    }
    if (bestScore < this.config.minFieldScore) {
      return { element: input, role: 'unknown', confidence: 0, visible };
    }
    return { element: input, role: bestRole, confidence: Math.min(bestScore / 8, 0.95), visible };
  }

  /**
   * Cluster fields into a logical form and infer intent.
   * Topology of password fields dominates; text signals only break ties.
   */
  classifyForm(fields: ClassifiedField[], container: HTMLElement, root: ScanRoot): ClassifiedForm {
    const resolved = this.resolvePasswordTopology(fields);
    const passwords = resolved.filter((f) => f.role.endsWith('password') && f.visible);
    const hasIdentity = resolved.some((f) => (f.role === 'username' || f.role === 'email') && f.visible);
    const hasCurrent = passwords.some((f) => f.role === 'current-password');
    const hasNew = passwords.some((f) => f.role === 'new-password');
    const hasConfirm = passwords.some((f) => f.role === 'confirm-password');

    const textHits = this.formTextHits(container);
    let intent: FormIntent = 'unknown';
    let confidence = 0.5;

    if (hasCurrent && hasNew) {
      intent = 'password-update';
      confidence = 0.9;
    } else if (hasNew || hasConfirm || passwords.length >= 2) {
      // No current password + new/confirm topology → register or reset.
      intent = textHits.has('password-reset') || !hasIdentity ? 'password-reset' : 'register';
      confidence = textHits.size > 0 ? 0.85 : 0.7;
    } else if (passwords.length === 1) {
      if (textHits.has('register')) {
        intent = 'register'; // single-password signup
        confidence = 0.75;
      } else {
        intent = 'login';
        confidence = hasIdentity ? 0.85 : 0.6;
      }
    } else if (hasIdentity && textHits.has('password-reset')) {
      intent = 'password-reset'; // email-only "forgot password" step
      confidence = 0.7;
    } else if (hasIdentity) {
      intent = textHits.has('register') ? 'register' : 'login'; // multi-step identifier-first flow
      confidence = 0.55;
    }

    return { container, intent, confidence, fields: resolved, root };
  }

  /**
   * Relational pass over password fields: singletons stay as classified; in pairs/triples,
   * an unmarked field following a new-password is a confirm; current precedes new.
   */
  private resolvePasswordTopology(fields: ClassifiedField[]): ClassifiedField[] {
    const pw = fields.filter((f) => f.element.type === 'password' && f.visible);
    if (pw.length < 2) return fields;

    const reassign = new Map<HTMLInputElement, FieldRole>();
    if (pw.length === 2) {
      const [a, b] = pw;
      if (a.role === 'current-password' && b.role === 'current-password' && a.confidence <= 0.4 && b.confidence <= 0.4) {
        // Two unmarked passwords: new + confirm (register/reset pattern).
        reassign.set(a.element, 'new-password');
        reassign.set(b.element, 'confirm-password');
      } else if (b.role !== 'confirm-password' && (a.role === 'new-password' || b.role === a.role)) {
        reassign.set(b.element, 'confirm-password');
      }
    } else if (pw.length >= 3) {
      reassign.set(pw[0].element, pw[0].confidence >= 0.6 ? pw[0].role : 'current-password');
      reassign.set(pw[1].element, 'new-password');
      reassign.set(pw[2].element, 'confirm-password');
    }

    return fields.map((f) => {
      const role = reassign.get(f.element);
      return role && role !== f.role ? { ...f, role, confidence: Math.max(f.confidence, 0.7) } : f;
    });
  }

  private formTextHits(container: HTMLElement): Set<FormIntent> {
    const parts: string[] = [container.getAttribute('id') ?? '', container.getAttribute('name') ?? ''];
    // getAttribute, not .action: an <input name="action"> clobbers the IDL property.
    if (isFormElement(container)) parts.push(container.getAttribute('action') ?? '');
    container
      .querySelectorAll<HTMLElement>('button, input[type="submit"], [role="button"], h1, h2, legend')
      .forEach((el, i) => {
        if (i < 8) parts.push((el.textContent ?? (el as HTMLInputElement).value ?? '').slice(0, 80));
      });
    const text = parts.join(' ');
    const hits = new Set<FormIntent>();
    for (const [intent, re] of Object.entries(INTENT_SIGNALS)) if (re.test(text)) hits.add(intent as FormIntent);
    return hits;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root registry + traversal (Shadow DOM / iframe piercing)
// ─────────────────────────────────────────────────────────────────────────────

interface DiscoveredRoot {
  readonly root: ScanRoot;
  readonly iframeDepth: number;
}

interface ScanResult {
  readonly inputs: HTMLInputElement[];
  readonly newRoots: readonly DiscoveredRoot[];
}

class RootRegistry {
  /** root → iframe nesting depth (shadow roots inherit their host root's depth). */
  private readonly known = new Map<ScanRoot, number>();

  has(root: ScanRoot): boolean {
    return this.known.has(root);
  }

  add(root: ScanRoot, iframeDepth: number): void {
    this.known.set(root, iframeDepth);
  }

  depthOf(root: ScanRoot): number {
    return this.known.get(root) ?? 0;
  }

  /** Removes dead roots (detached shadow hosts, discarded iframe documents) and returns them for cleanup. */
  prune(): ScanRoot[] {
    const removed: ScanRoot[] = [];
    for (const root of this.known.keys()) {
      if (!isRootAlive(root)) {
        this.known.delete(root);
        removed.push(root);
      }
    }
    return removed;
  }

  all(): readonly ScanRoot[] {
    return [...this.known.keys()];
  }

  clear(): void {
    this.known.clear();
  }

  /**
   * Iterative traversal of one root: collects fillable inputs, discovers open
   * shadow roots and same-origin iframe documents. Explicit stack — no recursion
   * limits, trivially time-sliceable.
   */
  scan(root: ScanRoot, iframeDepth: number, maxIframeDepth: number): ScanResult {
    const inputs: HTMLInputElement[] = [];
    const newRoots: DiscoveredRoot[] = [];
    const stack: Element[] = [];
    for (let c = root.lastElementChild; c; c = c.previousElementSibling) stack.push(c);

    while (stack.length > 0) {
      const el = stack.pop() as Element;

      if (isInput(el)) {
        if (isFillable(el)) inputs.push(el);
      } else if (isIframe(el) && iframeDepth < maxIframeDepth) {
        // Same-origin only; cross-origin frames run their own engine via all_frames injection.
        let doc: Document | null = null;
        try {
          doc = el.contentDocument;
        } catch {
          doc = null;
        }
        if (doc && !this.known.has(doc)) newRoots.push({ root: doc, iframeDepth: iframeDepth + 1 });
      }

      const shadow = el.shadowRoot; // open roots only; closed are unreachable by design
      if (shadow && !this.known.has(shadow)) newRoots.push({ root: shadow, iframeDepth });

      // Skip subtrees that can never contain fields.
      if (!/^(SCRIPT|STYLE|SVG|CANVAS|VIDEO|AUDIO|TEMPLATE|IFRAME)$/.test(el.tagName)) {
        for (let c = el.lastElementChild; c; c = c.previousElementSibling) stack.push(c);
      }
    }
    return { inputs, newRoots };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation Lifecycle Manager
// ─────────────────────────────────────────────────────────────────────────────

const RELEVANT_ATTRS = ['type', 'autocomplete', 'name', 'id', 'disabled', 'readonly', 'hidden', 'style', 'class'];

class MutationLifecycle {
  private readonly observers = new Map<ScanRoot, MutationObserver>();
  /** Roots that reported a relevant mutation since the last flush. */
  private readonly dirty = new Set<ScanRoot>();
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private maxWaitTimer: ReturnType<typeof setTimeout> | null = null;
  private cancelIdle: (() => void) | null = null;

  constructor(
    private readonly config: EngineConfig,
    private readonly onRescan: () => void,
  ) {}

  observe(root: ScanRoot): void {
    if (this.observers.has(root)) return;
    const observer = new MutationObserver((mutations) => {
      // Zero DOM reads here — only relevance filtering + dirty marking, then schedule.
      for (const m of mutations) {
        if (m.type === 'childList' && m.addedNodes.length === 0 && m.removedNodes.length === 0) continue;
        this.dirty.add(root);
        this.schedule();
        return;
      }
    });
    observer.observe(root, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: RELEVANT_ATTRS,
    });
    this.observers.set(root, observer);
  }

  /** Drains and returns the set of roots that mutated since the last drain. */
  takeDirty(): Set<ScanRoot> {
    const drained = new Set(this.dirty);
    this.dirty.clear();
    return drained;
  }

  /** Trailing-edge debounce with a max-wait ceiling so mutation storms still get scanned. */
  private schedule(): void {
    if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => this.flush(), this.config.debounceMs);
    if (this.maxWaitTimer === null) {
      this.maxWaitTimer = setTimeout(() => this.flush(), this.config.maxWaitMs);
    }
  }

  private flush(): void {
    if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
    if (this.maxWaitTimer !== null) clearTimeout(this.maxWaitTimer);
    this.debounceTimer = null;
    this.maxWaitTimer = null;
    if (this.cancelIdle !== null) return; // rescan already queued

    const run = (): void => {
      this.cancelIdle = null;
      this.onRescan();
    };
    if (typeof requestIdleCallback === 'function') {
      const handle = requestIdleCallback(run, { timeout: 500 });
      this.cancelIdle = () => cancelIdleCallback(handle);
    } else {
      const handle = setTimeout(run, 0);
      this.cancelIdle = () => clearTimeout(handle);
    }
  }

  disconnect(root: ScanRoot): void {
    this.observers.get(root)?.disconnect();
    this.observers.delete(root);
    this.dirty.delete(root);
  }

  dispose(roots: readonly ScanRoot[]): void {
    for (const root of roots) this.disconnect(root);
    if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
    if (this.maxWaitTimer !== null) clearTimeout(this.maxWaitTimer);
    this.debounceTimer = null;
    this.maxWaitTimer = null;
    this.cancelIdle?.();
    this.cancelIdle = null;
    this.dirty.clear();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Event Interceptor
// ─────────────────────────────────────────────────────────────────────────────

/** Anything that plausibly submits an SPA form: buttons of any type, submit/button inputs, ARIA buttons. */
function isSubmitLike(n: unknown): n is HTMLElement {
  if (!isElement(n)) return false;
  if (n.localName === 'button') return true;
  if (n.localName === 'input') {
    const t = (n as HTMLInputElement).type;
    return t === 'submit' || t === 'button' || t === 'image';
  }
  return n.getAttribute('role') === 'button';
}

class EventInterceptor {
  private readonly listenersByRoot = new Map<ScanRoot, Array<{ type: string; fn: EventListener }>>();
  private readonly lastEmit = new WeakMap<HTMLElement, number>();

  constructor(
    private readonly config: EngineConfig,
    private readonly resolveField: (el: HTMLInputElement) => ClassifiedField | null,
    private readonly resolveForm: (el: HTMLInputElement) => ClassifiedForm | null,
    private readonly resolveFormByNode: (node: Node) => ClassifiedForm | null,
    private readonly events: EngineEvents,
  ) {}

  attach(root: ScanRoot): void {
    if (this.listenersByRoot.has(root)) return;
    this.listenersByRoot.set(root, []);
    this.on(root, 'focusin', (e) => this.handleFocus(e));
    this.on(root, 'input', (e) => this.handleInput(e));
    this.on(root, 'keydown', (e) => this.handleKeydown(e));
    this.on(root, 'click', (e) => this.handleClick(e));
    this.on(root, 'submit', (e) => this.handleSubmit(e));
  }

  /** Remove this root's listeners so nothing keeps a strong reference to a pruned root. */
  detach(root: ScanRoot): void {
    const list = this.listenersByRoot.get(root);
    if (!list) return;
    for (const { type, fn } of list) root.removeEventListener(type, fn, { capture: true });
    this.listenersByRoot.delete(root);
  }

  private on(root: ScanRoot, type: string, fn: EventListener): void {
    root.addEventListener(type, fn, { capture: true, passive: true });
    this.listenersByRoot.get(root)?.push({ type, fn });
  }

  /** Recover the true target through shadow boundaries. */
  private target(e: Event): HTMLInputElement | null {
    const t = e.composedPath()[0] ?? e.target;
    return isInput(t) ? t : null;
  }

  private handleFocus(e: Event): void {
    const input = this.target(e);
    if (!input || !isFillable(input)) return;
    const field = this.resolveField(input);
    if (field && field.role !== 'unknown') {
      this.events.onFieldFocused?.(field, this.resolveForm(input));
    }
  }

  private handleInput(e: Event): void {
    const input = this.target(e);
    if (!input || input.type !== 'password') return;
    // Password typing inside an unclassified container ⇒ heuristics missed a
    // dynamically built form; force reconciliation via the form resolver
    // (rate-limited inside the engine — this fires per keystroke).
    this.resolveForm(input);
  }

  /** Enter-key submits in SPAs that never dispatch a submit event. */
  private handleKeydown(e: Event): void {
    if ((e as KeyboardEvent).key !== 'Enter') return;
    const input = this.target(e);
    if (!input || !isFillable(input)) return;
    const form = this.resolveForm(input);
    if (form) this.emitSubmission(form, true);
  }

  /**
   * Click-based submits (button onClick + fetch, no <form> submission) — the
   * dominant SPA login pattern. Values are snapshotted at pointer time, before
   * the app's own handler can clear the fields or unmount the view.
   */
  private handleClick(e: Event): void {
    const submitter = e.composedPath().find(isSubmitLike);
    if (!submitter) return;
    const form = this.resolveFormByNode(submitter);
    if (form && form.intent !== 'unknown') this.emitSubmission(form, true);
  }

  private handleSubmit(e: Event): void {
    const formEl = e.composedPath().find(isFormElement);
    const form = formEl ? this.resolveFormByNode(formEl) : null;
    if (form) this.emitSubmission(form, false);
  }

  /**
   * Snapshot values and emit. `requirePassword` guards the speculative paths
   * (Enter/click) against noise; the real submit event always emits. Emissions
   * for the same container are deduped (click and submit often both fire).
   */
  private emitSubmission(form: ClassifiedForm, requirePassword: boolean): void {
    const now = Date.now();
    if (now - (this.lastEmit.get(form.container) ?? 0) < this.config.submitDedupeMs) return;

    const captured: Partial<Record<FieldRole, string>> = {};
    for (const f of form.fields) {
      if (f.role !== 'unknown' && f.element.value) captured[f.role] = f.element.value;
    }
    if (
      requirePassword &&
      captured['current-password'] === undefined &&
      captured['new-password'] === undefined &&
      captured['confirm-password'] === undefined
    ) {
      return;
    }
    this.lastEmit.set(form.container, now);
    this.events.onFormSubmitted?.(form, captured);
  }

  dispose(): void {
    for (const root of [...this.listenersByRoot.keys()]) this.detach(root);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// AutofillEngine — public facade
// ─────────────────────────────────────────────────────────────────────────────

export class AutofillEngine {
  private readonly config: EngineConfig;
  private readonly registry = new RootRegistry();
  private readonly heuristics: HeuristicEngine;
  private readonly lifecycle: MutationLifecycle;
  private readonly interceptor: EventInterceptor;
  /** Per-root input cache: clean roots are not re-walked on rescan. */
  private readonly inputsByRoot = new Map<ScanRoot, HTMLInputElement[]>();
  private forms: ClassifiedForm[] = [];
  private started = false;
  private lastForcedRescan = 0;

  constructor(
    private readonly events: EngineEvents = {},
    config: Partial<EngineConfig> = {},
  ) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.heuristics = new HeuristicEngine(this.config);
    this.lifecycle = new MutationLifecycle(this.config, () => this.rescan());
    this.interceptor = new EventInterceptor(
      this.config,
      (el) => this.heuristics.classifyField(el),
      (el) => this.formFor(el),
      (node) => this.formForNode(node),
      this.events,
    );
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    this.adoptRoot(document, 0);
    this.rescan(true);
  }

  stop(): void {
    this.started = false;
    this.lifecycle.dispose(this.registry.all());
    this.interceptor.dispose();
    this.registry.clear();
    this.inputsByRoot.clear();
    this.forms = [];
  }

  getForms(): readonly ClassifiedForm[] {
    return this.forms;
  }

  /** Fill a classified form with a credential, honoring per-role targets. */
  fill(form: ClassifiedForm, credential: Credential): boolean {
    let filled = false;
    for (const field of form.fields) {
      if (!field.visible || !field.element.isConnected) continue;
      const value = this.valueFor(field.role, credential);
      if (value === undefined) continue;
      // Focus before writing: several frameworks gate change handlers on focus
      // state, and the focus shift onto the next field produces the blur that
      // triggers per-field validation, mirroring real user input. Focus stays
      // on the last filled field, which is the natural post-autofill state.
      field.element.focus({ preventScroll: true });
      setNativeValue(field.element, value);
      filled = true;
    }
    return filled;
  }

  private valueFor(role: FieldRole, c: Credential): string | undefined {
    switch (role) {
      case 'username':
      case 'email':
        return c.username;
      case 'current-password':
        return c.password;
      case 'new-password':
      case 'confirm-password':
        return c.newPassword ?? c.password;
      default:
        return undefined;
    }
  }

  private adoptRoot(root: ScanRoot, iframeDepth: number): void {
    if (this.registry.has(root)) return;
    this.registry.add(root, iframeDepth);
    this.lifecycle.observe(root);
    this.interceptor.attach(root);
  }

  /**
   * Reconciliation pass. Only dirty roots (those that reported mutations) and
   * never-scanned roots are re-walked; clean roots reuse their cached inputs.
   * `force` rescans everything (initial scan, event-driven reconciliation).
   */
  private rescan(force = false): void {
    if (!this.started) return;

    // Explicit teardown is required: an active MutationObserver holds a strong
    // internal reference to its observed node, and the interceptor's listener
    // table holds strong references to roots — either would keep a detached
    // shadow root or discarded iframe document alive despite WeakMap usage.
    for (const stale of this.registry.prune()) {
      this.lifecycle.disconnect(stale);
      this.interceptor.detach(stale);
      this.inputsByRoot.delete(stale);
    }

    const dirty = this.lifecycle.takeDirty();
    const queue: ScanRoot[] = this.registry.all().filter((r) => force || dirty.has(r) || !this.inputsByRoot.has(r));

    while (queue.length > 0) {
      const root = queue.shift() as ScanRoot;
      const { inputs, newRoots } = this.registry.scan(root, this.registry.depthOf(root), this.config.maxIframeDepth);
      this.inputsByRoot.set(root, inputs);
      for (const discovered of newRoots) {
        this.adoptRoot(discovered.root, discovered.iframeDepth);
        queue.push(discovered.root);
      }
    }

    // Merge per-root caches; cheap isConnected filter drops nodes removed from
    // clean roots between their last scan and now.
    const allInputs: HTMLInputElement[] = [];
    for (const root of this.registry.all()) {
      for (const input of this.inputsByRoot.get(root) ?? []) {
        if (input.isConnected) allInputs.push(input);
      }
    }

    const nextForms = this.buildForms(allInputs);
    if (this.formsChanged(nextForms)) {
      this.forms = nextForms;
      this.events.onFormsChanged?.(nextForms);
    }
  }

  private buildForms(inputs: HTMLInputElement[]): ClassifiedForm[] {
    // Cluster by owning <form>; form-less fields grouped per root, container = common ancestor.
    const byForm = new Map<HTMLFormElement, ClassifiedField[]>();
    const orphansByRoot = new Map<ScanRoot, ClassifiedField[]>();

    for (const input of inputs) {
      const field = this.heuristics.classifyField(input);
      if (field.role === 'unknown' && input.type !== 'password') continue;
      const owner = input.form;
      if (owner) {
        (byForm.get(owner) ?? byForm.set(owner, []).get(owner)!).push(field);
      } else {
        const root = input.getRootNode() as ScanRoot;
        (orphansByRoot.get(root) ?? orphansByRoot.set(root, []).get(root)!).push(field);
      }
    }

    const forms: ClassifiedForm[] = [];
    for (const [formEl, fields] of byForm) {
      forms.push(this.heuristics.classifyForm(fields, formEl, formEl.getRootNode() as ScanRoot));
    }
    for (const [root, fields] of orphansByRoot) {
      const elements = fields.map((f) => f.element);
      forms.push(this.heuristics.classifyForm(fields, commonAncestor(elements), root));
    }
    return forms.filter((f) => f.intent !== 'unknown' || f.fields.some((x) => x.element.type === 'password'));
  }

  private formFor(input: HTMLInputElement): ClassifiedForm | null {
    const find = (): ClassifiedForm | null =>
      this.forms.find((form) => form.fields.some((f) => f.element === input)) ?? null;

    const direct = find();
    if (direct) return direct;

    // Unknown input ⇒ possibly stale state. Reconcile synchronously, but
    // rate-limited: this path is reachable from per-keystroke input events and
    // a full forest walk forces layout.
    const now = Date.now();
    if (now - this.lastForcedRescan < this.config.forcedRescanCooldownMs) return null;
    this.lastForcedRescan = now;
    this.rescan(true);
    return find();
  }

  private formForNode(node: Node): ClassifiedForm | null {
    return this.forms.find((f) => f.container === node || f.container.contains(node)) ?? null;
  }

  private formsChanged(next: ClassifiedForm[]): boolean {
    if (next.length !== this.forms.length) return true;
    for (let i = 0; i < next.length; i++) {
      const a = next[i];
      const b = this.forms[i];
      if (a.container !== b.container || a.intent !== b.intent || a.fields.length !== b.fields.length) return true;
      for (let j = 0; j < a.fields.length; j++) {
        const fa = a.fields[j];
        const fb = b.fields[j];
        if (fa.element !== fb.element || fa.role !== fb.role || fa.visible !== fb.visible) return true;
      }
    }
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bootstrap (content-script entry — wire events to the extension runtime here)
// ─────────────────────────────────────────────────────────────────────────────

export function bootstrapAutofillEngine(events: EngineEvents = {}): AutofillEngine {
  const engine = new AutofillEngine(events);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => engine.start(), { once: true });
  } else {
    engine.start();
  }
  return engine;
}
