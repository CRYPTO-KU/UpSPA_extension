use crate::hash::{hash_to_point, oprf_finalize};
use crate::types::UpspaError;
use curve25519_dalek::{
    ristretto::{CompressedRistretto, RistrettoPoint},
    scalar::Scalar,
    traits::Identity,
};
use rand_core::RngCore;
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToprfPartial {
    pub id: u32,
    pub y: [u8; 32],
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToprfClientState {
    pub r: [u8; 32],
}
pub struct ToprfClient;
impl ToprfClient {
    pub fn begin(password: &[u8], rng: &mut impl RngCore) -> (ToprfClientState, [u8; 32]) {
        let r = random_scalar(rng);
        let p = hash_to_point(password);
        let blinded = p * r;
        let blinded_bytes = blinded.compress().to_bytes();
        (ToprfClientState { r: r.to_bytes() }, blinded_bytes)
    }
    pub fn finish(
        password: &[u8],
        state: &ToprfClientState,
        partials: &[ToprfPartial],
    ) -> Result<[u8; 32], UpspaError> {
        if partials.is_empty() {
            return Err(UpspaError::InvalidLength {
                expected: 1,
                got: 0,
            });
        }
        let r = scalar_from_canonical_bytes(&state.r)?;
        if r == Scalar::ZERO {
            return Err(UpspaError::InvalidScalar);
        }
        let ids: Vec<u32> = partials.iter().map(|p| p.id).collect();
        let lambdas = lagrange_coeffs_at_zero(&ids);
        let mut acc = RistrettoPoint::identity();
        for (p, l) in partials.iter().zip(lambdas) {
            let y_i = point_from_bytes(&p.y)?;
            acc += y_i * l;
        }
        let y = acc * r.invert();
        Ok(oprf_finalize(password, &y))
    }
}
pub fn toprf_gen(nsp: usize, tsp: usize, rng: &mut impl RngCore) -> (Scalar, Vec<(u32, Scalar)>) {
    assert!(tsp >= 1 && tsp <= nsp);
    let a0 = random_scalar(rng);
    let mut coeffs = vec![a0];
    for _ in 1..tsp {
        coeffs.push(random_scalar(rng));
    }
    fn eval(coeffs: &[Scalar], x: Scalar) -> Scalar {
        let mut acc = Scalar::ZERO;
        let mut pow = Scalar::ONE;
        for c in coeffs {
            acc += c * pow;
            pow *= x;
        }
        acc
    }
    let mut shares = Vec::with_capacity(nsp);
    for i in 1..=nsp {
        shares.push((i as u32, eval(&coeffs, Scalar::from(i as u64))));
    }
    (a0, shares)
}
pub fn lagrange_coeffs_at_zero(ids: &[u32]) -> Vec<Scalar> {
    let xs: Vec<Scalar> = ids.iter().map(|&i| Scalar::from(i as u64)).collect();
    let mut lambdas = Vec::with_capacity(xs.len());
    for i in 0..xs.len() {
        let mut num = Scalar::ONE;
        let mut den = Scalar::ONE;
        for j in 0..xs.len() {
            if i != j {
                num *= xs[j];
                den *= xs[j] - xs[i];
            }
        }
        lambdas.push(num * den.invert());
    }
    lambdas
}
pub fn toprf_client_eval(
    password: &[u8],
    r: Scalar,
    partials: &[RistrettoPoint],
    lambdas: &[Scalar],
) -> [u8; 32] {
    let p = hash_to_point(password);
    let blinded = p * r;
    std::hint::black_box(blinded.compress());
    let mut acc = RistrettoPoint::identity();
    for (y, l) in partials.iter().zip(lambdas) {
        acc += y * l;
    }
    let y = acc * r.invert();
    oprf_finalize(password, &y)
}
pub fn toprf_client_eval_from_partials(
    password: &[u8],
    r: Scalar,
    partials: &[RistrettoPoint],
    lambdas: &[Scalar],
) -> [u8; 32] {
    let mut acc = RistrettoPoint::identity();
    for (y, l) in partials.iter().zip(lambdas) {
        acc += y * l;
    }
    let y = acc * r.invert();
    oprf_finalize(password, &y)
}
pub fn random_scalar(rng: &mut impl RngCore) -> Scalar {
    loop {
        let mut wide = [0u8; 64];
        rng.fill_bytes(&mut wide);
        let s = Scalar::from_bytes_mod_order_wide(&wide);
        if s != Scalar::ZERO {
            return s;
        }
    }
}
pub fn point_from_bytes(bytes: &[u8; 32]) -> Result<RistrettoPoint, UpspaError> {
    let p = CompressedRistretto(*bytes)
        .decompress()
        .ok_or(UpspaError::InvalidRistrettoPoint)?;
    Ok(p)
}
pub fn scalar_from_canonical_bytes(bytes: &[u8; 32]) -> Result<Scalar, UpspaError> {
    Option::<Scalar>::from(Scalar::from_canonical_bytes(*bytes)).ok_or(UpspaError::InvalidScalar)
}
pub fn toprf_server_eval(blinded: &[u8; 32], share: &[u8; 32]) -> Result<[u8; 32], UpspaError> {
    let b = point_from_bytes(blinded)?;
    let k = scalar_from_canonical_bytes(share)?;
    let y = b * k;
    Ok(y.compress().to_bytes())
}
