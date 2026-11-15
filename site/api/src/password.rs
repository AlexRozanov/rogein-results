use anyhow::{bail, Result};
use argon2::password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;

pub const MIN_PASSWORD_LEN: usize = 10;

pub fn normalize_email(raw: &str) -> Result<String> {
    let email = raw.trim().to_lowercase();
    if email.is_empty() || !email.contains('@') || email.starts_with('@') || email.ends_with('@') {
        bail!("Нужен email вида name@domain");
    }
    if email.chars().any(char::is_whitespace) {
        bail!("В email не должно быть пробелов");
    }
    Ok(email)
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    let Ok(parsed) = PasswordHash::new(hash) else {
        return false;
    };
    Argon2::default()
        .verify_password(password.as_bytes(), &parsed)
        .is_ok()
}

pub fn hash_password(password: &str) -> Result<String> {
    if password.chars().count() < MIN_PASSWORD_LEN {
        bail!("Пароль не короче {MIN_PASSWORD_LEN} символов");
    }
    if password.chars().any(|c| c.is_control()) {
        bail!("Пароль не должен содержать управляющие символы");
    }
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| anyhow::anyhow!("не удалось посчитать hash пароля: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn email_is_trimmed_and_lowercased() {
        assert_eq!(normalize_email("  Owner@Malahit.LOCAL ").unwrap(), "owner@malahit.local");
    }

    #[test]
    fn email_rejects_junk() {
        assert!(normalize_email("").is_err());
        assert!(normalize_email("no-at").is_err());
        assert!(normalize_email("a @b.c").is_err());
    }

    #[test]
    fn password_too_short() {
        assert!(hash_password("short").is_err());
    }

    #[test]
    fn password_hash_is_argon2() {
        let hash = hash_password("correct-horse").unwrap();
        assert!(hash.starts_with("$argon2"));
        assert!(verify_password("correct-horse", &hash));
        assert!(!verify_password("wrong-password", &hash));
    }
}
