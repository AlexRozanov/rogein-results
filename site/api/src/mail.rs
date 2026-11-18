use anyhow::{bail, Context, Result};
use lettre::message::{Mailbox, MessageBuilder, MultiPart};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{AsyncSmtpTransport, AsyncTransport, Tokio1Executor};
use tracing::{info, warn};

#[derive(Clone, Debug, PartialEq, Eq)]
enum MailDriver {
    Smtp,
    Resend,
}

#[derive(Clone, Debug)]
pub struct MailConfig {
    pub enabled: bool,
    pub public_base_url: String,
    pub mail_from: String,
    driver: Option<MailDriver>,
    smtp_host: String,
    smtp_port: u16,
    smtp_user: String,
    smtp_password: String,
    resend_api_key: String,
}

impl MailConfig {
    pub fn from_env() -> Self {
        let public_base_url = std::env::var("PUBLIC_BASE_URL")
            .or_else(|_| std::env::var("SITE_ORIGIN"))
            .unwrap_or_else(|_| "http://127.0.0.1:5174".into())
            .trim()
            .trim_end_matches('/')
            .to_string();
        let mail_from = std::env::var("MAIL_FROM")
            .unwrap_or_else(|_| "noreply@localhost".into())
            .trim()
            .to_string();
        let smtp_host = std::env::var("SMTP_HOST").unwrap_or_default().trim().to_string();
        let smtp_port = std::env::var("SMTP_PORT")
            .ok()
            .and_then(|v| v.trim().parse().ok())
            .unwrap_or(587);
        let smtp_user = std::env::var("SMTP_USER").unwrap_or_default().trim().to_string();
        let smtp_password = std::env::var("SMTP_PASSWORD").unwrap_or_default();
        let resend_api_key = std::env::var("RESEND_API_KEY").unwrap_or_default().trim().to_string();
        let driver_raw = std::env::var("MAIL_DRIVER")
            .unwrap_or_else(|_| "auto".into())
            .trim()
            .to_ascii_lowercase();

        let driver = match driver_raw.as_str() {
            "smtp" => {
                if smtp_host.is_empty() {
                    warn!("MAIL_DRIVER=smtp, но SMTP_HOST пуст — письма отключены");
                    None
                } else {
                    Some(MailDriver::Smtp)
                }
            }
            "resend" => {
                if resend_api_key.is_empty() {
                    warn!("MAIL_DRIVER=resend, но RESEND_API_KEY пуст — письма отключены");
                    None
                } else {
                    Some(MailDriver::Resend)
                }
            }
            "auto" | "" => {
                if !resend_api_key.is_empty() {
                    Some(MailDriver::Resend)
                } else if !smtp_host.is_empty() {
                    Some(MailDriver::Smtp)
                } else {
                    None
                }
            }
            other => {
                warn!(driver = other, "неизвестный MAIL_DRIVER — письма отключены");
                None
            }
        };

        let enabled = driver.is_some();
        match &driver {
            Some(MailDriver::Resend) => info!("почта: Resend"),
            Some(MailDriver::Smtp) => info!(host = %smtp_host, "почта: SMTP"),
            None => info!("почта не настроена — письма не отправляются"),
        }

        Self {
            enabled,
            public_base_url,
            mail_from,
            driver,
            smtp_host,
            smtp_port,
            smtp_user,
            smtp_password,
            resend_api_key,
        }
    }

    pub fn invite_url(&self, token: &str) -> String {
        format!("{}/admin/invite/{}", self.public_base_url, token)
    }

    pub fn reset_url(&self, token: &str) -> String {
        format!("{}/admin/reset/{}", self.public_base_url, token)
    }

    pub async fn send_invite(&self, to_email: &str, invite_url: &str) -> Result<()> {
        if !self.enabled {
            bail!("почта не настроена (SMTP или Resend)");
        }
        let subject = "Приглашение в админку СК Малахит";
        let text = format!(
            "Вас пригласили в админку сайта СК Малахит.\n\n\
             Откройте ссылку и задайте пароль (действует 72 часа):\n\
             {invite_url}\n\n\
             Если вы не ждали это письмо, просто игнорируйте его.\n"
        );
        let html = format!(
            "<p>Вас пригласили в админку сайта СК Малахит.</p>\
             <p><a href=\"{invite_url}\">Открыть приглашение и задать пароль</a></p>\
             <p>Ссылка действует 72 часа. Если вы не ждали это письмо, игнорируйте его.</p>"
        );
        self.send(to_email, subject, &text, &html).await
    }

    pub async fn send_password_reset(&self, to_email: &str, reset_url: &str) -> Result<()> {
        if !self.enabled {
            bail!("почта не настроена (SMTP или Resend)");
        }
        let subject = "Сброс пароля админки СК Малахит";
        let text = format!(
            "Запрошен сброс пароля для входа в админку СК Малахит.\n\n\
             Откройте ссылку и задайте новый пароль (действует 2 часа):\n\
             {reset_url}\n\n\
             Если вы не запрашивали сброс, просто игнорируйте это письмо.\n"
        );
        let html = format!(
            "<p>Запрошен сброс пароля для входа в админку СК Малахит.</p>\
             <p><a href=\"{reset_url}\">Задать новый пароль</a></p>\
             <p>Ссылка действует 2 часа. Если вы не запрашивали сброс, игнорируйте это письмо.</p>"
        );
        self.send(to_email, subject, &text, &html).await
    }

    async fn send(&self, to_email: &str, subject: &str, text: &str, html: &str) -> Result<()> {
        match self.driver.as_ref() {
            Some(MailDriver::Resend) => self.send_resend(to_email, subject, text, html).await,
            Some(MailDriver::Smtp) => self.send_smtp(to_email, subject, text, html).await,
            None => bail!("почта не настроена"),
        }
    }

    async fn send_resend(
        &self,
        to_email: &str,
        subject: &str,
        text: &str,
        html: &str,
    ) -> Result<()> {
        let client = reqwest::Client::new();
        let body = serde_json::json!({
            "from": self.mail_from,
            "to": [to_email],
            "subject": subject,
            "text": text,
            "html": html,
        });
        let response = client
            .post("https://api.resend.com/emails")
            .bearer_auth(&self.resend_api_key)
            .json(&body)
            .send()
            .await
            .context("Resend HTTP")?;
        let status = response.status();
        let response_body = response.text().await.unwrap_or_default();
        if !status.is_success() {
            warn!(
                to = to_email,
                status = %status,
                body = %response_body.chars().take(300).collect::<String>(),
                "Resend отклонил письмо"
            );
            bail!("Resend {status}: {}", response_body.chars().take(200).collect::<String>());
        }
        info!(to = to_email, "письмо отправлено через Resend");
        Ok(())
    }

    async fn send_smtp(&self, to_email: &str, subject: &str, text: &str, html: &str) -> Result<()> {
        let from: Mailbox = self
            .mail_from
            .parse()
            .with_context(|| format!("MAIL_FROM '{}'", self.mail_from))?;
        let to: Mailbox = to_email
            .parse()
            .with_context(|| format!("адрес получателя '{to_email}'"))?;
        let message = MessageBuilder::new()
            .from(from)
            .to(to)
            .subject(subject)
            .multipart(MultiPart::alternative_plain_html(
                text.to_string(),
                html.to_string(),
            ))
            .context("собрать письмо")?;

        let mut builder = AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&self.smtp_host)
            .with_context(|| format!("SMTP host '{}'", self.smtp_host))?
            .port(self.smtp_port);
        if !self.smtp_user.is_empty() {
            builder = builder.credentials(Credentials::new(
                self.smtp_user.clone(),
                self.smtp_password.clone(),
            ));
        }
        let mailer = builder.build();
        match mailer.send(message).await {
            Ok(_) => {
                info!(to = to_email, "письмо отправлено через SMTP");
                Ok(())
            }
            Err(err) => {
                warn!(to = to_email, error = %err, "не удалось отправить письмо");
                Err(err).context("SMTP send")
            }
        }
    }
}
