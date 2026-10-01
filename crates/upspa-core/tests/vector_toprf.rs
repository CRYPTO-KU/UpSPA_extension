use curve25519_dalek::ristretto::RistrettoPoint;
use curve25519_dalek::scalar::Scalar;
use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use upspa_core::hash::{hash_to_point, oprf_finalize};
use upspa_core::toprf::toprf_gen;
use upspa_core::types::UpspaError;
fn rng_from_seed(byte: u8) -> ChaCha20Rng {
    let mut seed = [0u8; 32];
    seed.fill(byte);
    ChaCha20Rng::from_seed(seed)
}
fn lagrange_at_zero(xs: &[Scalar], i: usize) -> Scalar {
    let x_i = xs[i];
    let mut num = Scalar::ONE;
    let mut den = Scalar::ONE;
    for (j, x_j) in xs.iter().enumerate() {
        if j == i {
            continue;
        }
        num *= -(*x_j);
        den *= x_i - *x_j;
    }
    num * den.invert()
}
fn combine_in_exponent(xs: &[Scalar], ys: &[RistrettoPoint]) -> RistrettoPoint {
    assert_eq!(xs.len(), ys.len());
    let mut acc = RistrettoPoint::default();
    for i in 0..xs.len() {
        let lambda = lagrange_at_zero(xs, i);
        acc += ys[i] * lambda;
    }
    acc
}
#[test]
fn toprf_threshold_reconstruction_matches_master() -> Result<(), UpspaError> {
    let nsp: usize = 5;
    let tsp: usize = 3;
    let pw = b"toprf vector password";
    let p = hash_to_point(pw);
    let mut rng = rng_from_seed(0x42);
    let (k_master, shares) = toprf_gen(nsp, tsp, &mut rng);
    let mut xs: Vec<Scalar> = Vec::with_capacity(tsp);
    let mut ys: Vec<RistrettoPoint> = Vec::with_capacity(tsp);
    for (idx, (sp_id, k_i)) in shares.iter().enumerate() {
        if idx >= tsp {
            break;
        }
        let x_i = Scalar::from(*sp_id as u64);
        xs.push(x_i);
        ys.push(p * (*k_i));
    }
    let y_reconstructed = combine_in_exponent(&xs, &ys);
    let y_direct = p * k_master;
    assert_eq!(
        y_reconstructed, y_direct,
        "threshold reconstruction in exponent must match direct master evaluation"
    );
    let key1 = oprf_finalize(pw, &y_reconstructed);
    let key2 = oprf_finalize(pw, &y_direct);
    assert_eq!(key1, key2);
    Ok(())
}
#[test]
fn toprf_reconstruction_fails_with_t_minus_1_shares() -> Result<(), UpspaError> {
    let nsp: usize = 5;
    let tsp: usize = 3;
    let pw = b"toprf threshold negative test";
    let p = hash_to_point(pw);
    let mut rng = rng_from_seed(0x99);
    let (k_master, shares) = toprf_gen(nsp, tsp, &mut rng);
    let take = tsp - 1;
    let mut xs: Vec<Scalar> = Vec::with_capacity(take);
    let mut ys: Vec<RistrettoPoint> = Vec::with_capacity(take);
    for (idx, (sp_id, k_i)) in shares.iter().enumerate() {
        if idx >= take {
            break;
        }
        xs.push(Scalar::from(*sp_id as u64));
        ys.push(p * (*k_i));
    }
    let y_bad = combine_in_exponent(&xs, &ys);
    let y_direct = p * k_master;
    assert_ne!(y_bad, y_direct);
    Ok(())
}
