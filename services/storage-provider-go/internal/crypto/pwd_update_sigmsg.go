// =============================================================================
// UpSPA - Storage Provider crypto (services/storage-provider-go/internal/crypto)
// Reviewed and annotated by Efe Bektes (intern, ITU), May 2026.
// This file: the exact byte layout the client signs to authorize a password update.
// Change:    documented the byte layout; covered by a golden vector so it cannot silently drift.
// Reviewed with AI assistance; verified against the crypto test suite before commit.
// =============================================================================
// pwd_update_sigmsg.go — build the exact bytes the client signs to authorize a
// password update. Getting this byte layout wrong silently breaks every update.

package crypto

import (
	"encoding/binary"
)

// BuildPwdUpdateSigMsg lays out, in one fixed order, everything a password-update
// signature must commit to. The client signs these exact bytes; the SP rebuilds the
// same bytes and checks the signature with VerifyEd25519.
//
// Plain version: when a user changes their master password, the SP must be sure the
// request is genuine and cannot be replayed or tampered with. So the signature covers:
//   - the new encrypted blob (nonce, ct, tag) -> ties the signature to this exact new data
//   - the new secret share kINew              -> nobody can swap in a different key
//   - the timestamp (8 bytes, little-endian)  -> an old request cannot be replayed
//   - the SP id (4 bytes, little-endian)       -> a request for SP 1 will not work on SP 2
//
// Order and byte-width must match the client exactly. The layout is:
//
//	nonce | ct | tag | kINew | timestamp(8, little-endian) | spID(4, little-endian)
func BuildPwdUpdateSigMsg(
	cidNonce []byte,
	cidCt []byte,
	cidTag []byte,
	kINew []byte,
	tsU64LE uint64,
	spIDU32LE uint32,
) []byte {
	// Encode the timestamp as 8 little-endian bytes.
	tsBytes := make([]byte, 8)
	binary.LittleEndian.PutUint64(tsBytes, tsU64LE)

	// Encode the SP id as 4 little-endian bytes.
	spIDBytes := make([]byte, 4)
	binary.LittleEndian.PutUint32(spIDBytes, spIDU32LE)

	// Allocate once with the exact final size, then append each field in order.
	totalLen := len(cidNonce) + len(cidCt) + len(cidTag) + len(kINew) + 8 + 4
	msg := make([]byte, 0, totalLen)

	msg = append(msg, cidNonce...)
	msg = append(msg, cidCt...)
	msg = append(msg, cidTag...)
	msg = append(msg, kINew...)
	msg = append(msg, tsBytes...)
	msg = append(msg, spIDBytes...)
	return msg
}
