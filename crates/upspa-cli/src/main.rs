use anyhow::{anyhow, Context, Result};
use clap::{Parser, Subcommand};
use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use upspa_core::protocol::{authenticate, password_update, register, secret_update, setup};
use upspa_core::toprf::{toprf_server_eval, ToprfClient, ToprfPartial};
use upspa_core::types::{b64_encode, CtBlobB64};
#[derive(Parser, Debug)]
#[command(name = "upspa")]
#[command(version)]
#[command(about = "UpSPA developer CLI", long_about = None)]
struct Cli {
    #[command(subcommand)]
    cmd: Command,
}
#[derive(Subcommand, Debug)]
enum Command {
    Setup {
        #[arg(long)]
        uid: String,
        #[arg(long)]
        password: String,
        #[arg(long, default_value_t = 5)]
        nsp: usize,
        #[arg(long, default_value_t = 3)]
        tsp: usize,
        #[arg(long)]
        seed_hex: Option<String>,
    },
    DemoFlow {
        #[arg(long)]
        uid: String,
        #[arg(long)]
        lsj: String,
        #[arg(long)]
        password: String,
        #[arg(long)]
        new_password: String,
        #[arg(long, default_value_t = 5)]
        nsp: usize,
        #[arg(long, default_value_t = 3)]
        tsp: usize,
    },
}
fn parse_seed(seed_hex: Option<String>) -> Result<[u8; 32]> {
    let mut out = [0u8; 32];
    if let Some(h) = seed_hex {
        let bytes = hex::decode(h).map_err(|e| anyhow!("invalid hex seed: {e}"))?;
        if bytes.len() != 32 {
            return Err(anyhow!("seed_hex must be 32 bytes (64 hex chars)"));
        }
        out.copy_from_slice(&bytes);
        Ok(out)
    } else {
        Ok([42u8; 32])
    }
}
fn ct_to_b64<const N: usize>(ct: &upspa_core::types::CtBlob<N>) -> CtBlobB64 {
    ct.to_b64()
}
fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.cmd {
        Command::Setup {
            uid,
            password,
            nsp,
            tsp,
            seed_hex,
        } => {
            let seed = parse_seed(seed_hex)?;
            let mut rng = ChaCha20Rng::from_seed(seed);
            let (out, payloads) =
                setup::client_setup(uid.as_bytes(), password.as_bytes(), nsp, tsp, &mut rng);
            let json = serde_json::json!({
                "sig_pk_b64": b64_encode(&out.sig_pk),
                "cid": ct_to_b64(&out.cid),
                "shares": out.shares.iter().map(|(id, k)| serde_json::json!({"sp_id": id, "k_i_b64": b64_encode(k)})).collect::<Vec<_>>(),
                "sp_payloads": payloads.iter().map(|p| serde_json::json!({
                    "sp_id": p.sp_id,
                    "uid_b64": b64_encode(&p.uid),
                    "sig_pk_b64": b64_encode(&p.sig_pk),
                    "cid": ct_to_b64(&p.cid),
                    "k_i_b64": b64_encode(&p.k_i),
                })).collect::<Vec<_>>()
            });
            println!("{}", serde_json::to_string_pretty(&json)?);
        }
        Command::DemoFlow {
            uid,
            lsj,
            password,
            new_password,
            nsp,
            tsp,
        } => {
            let mut rng = ChaCha20Rng::from_seed([7u8; 32]);
            let (setup_out, _payloads) =
                setup::client_setup(uid.as_bytes(), password.as_bytes(), nsp, tsp, &mut rng);
            let (st, blinded) = ToprfClient::begin(password.as_bytes(), &mut rng);
            let mut partials = Vec::new();
            for (id, share_bytes) in setup_out.shares.iter().take(tsp) {
                let y_i = toprf_server_eval(&blinded, share_bytes).context("toprf_server_eval")?;
                partials.push(ToprfPartial { id: *id, y: y_i });
            }
            let state_key = ToprfClient::finish(password.as_bytes(), &st, &partials)?;
            let reg = register::client_register(
                uid.as_bytes(),
                lsj.as_bytes(),
                &state_key,
                &setup_out.cid,
                nsp,
                &mut rng,
            )?;
            let auth_q = authenticate::client_auth_prepare(
                uid.as_bytes(),
                lsj.as_bytes(),
                &state_key,
                &setup_out.cid,
                nsp,
            )?;
            let cjs = reg
                .per_sp
                .iter()
                .take(tsp)
                .map(|m| m.cj.clone())
                .collect::<Vec<_>>();
            let auth_res =
                authenticate::client_auth_finish(uid.as_bytes(), lsj.as_bytes(), &auth_q.k0, &cjs)?;
            let su_q = secret_update::client_secret_update_prepare(
                uid.as_bytes(),
                lsj.as_bytes(),
                &state_key,
                &setup_out.cid,
                nsp,
            )?;
            let su_res = secret_update::client_secret_update_finish(
                uid.as_bytes(),
                lsj.as_bytes(),
                &su_q.k0,
                &cjs,
                &mut rng,
            )?;
            let timestamp = 1_700_000_000u64;
            let pw_res = password_update::client_password_update(
                uid.as_bytes(),
                &state_key,
                &setup_out.cid,
                nsp,
                tsp,
                new_password.as_bytes(),
                timestamp,
                &mut rng,
            )?;
            let json = serde_json::json!({
                "setup": {
                    "sig_pk_b64": b64_encode(&setup_out.sig_pk),
                    "cid": ct_to_b64(&setup_out.cid),
                },
                "registration": {
                    "to_ls": {
                        "uid": uid,
                        "vinfo_b64": b64_encode(&reg.to_ls.vinfo)
                    },
                    "per_sp": reg.per_sp.iter().map(|m| serde_json::json!({
                        "sp_id": m.sp_id,
                        "suid_b64": b64_encode(&m.suid),
                        "cj": ct_to_b64(&m.cj)
                    })).collect::<Vec<_>>()
                },
                "authentication": {
                    "vinfo_prime_b64": b64_encode(&auth_res.vinfo_prime),
                    "best_ctr": auth_res.best_ctr,
                },
                "secret_update": {
                    "vinfo_prime_b64": b64_encode(&su_res.vinfo_prime),
                    "vinfo_new_b64": b64_encode(&su_res.vinfo_new),
                    "cj_new": ct_to_b64(&su_res.cj_new),
                    "old_ctr": su_res.old_ctr,
                    "new_ctr": su_res.new_ctr,
                },
                "password_update": {
                    "cid_new": ct_to_b64(&pw_res.cid_new),
                    "per_sp": pw_res.per_sp.iter().map(|m| serde_json::json!({
                        "sp_id": m.sp_id,
                        "sig_b64": b64_encode(&m.sig),
                        "k_i_new_b64": b64_encode(&m.k_i_new)
                    })).collect::<Vec<_>>()
                }
            });
            println!("{}", serde_json::to_string_pretty(&json)?);
        }
    }
    Ok(())
}
