//! Free conversion and error helpers shared by more than one backend:
//! the completed-coroutine error mapping (with its HTTP-status probe)
//! and the push-change field and outcome helpers the Graph and JMAP
//! push paths both lean on.

use core::error::Error as StdError;

use io_google_people::v1::send::PeopleSendError;
use io_jmap::rfc8620::send::JmapSendError;
use io_msgraph::v1::send::MsgraphSendError;
use io_webdav::rfc4918::{
    follow_redirects::FollowRedirectsError, send::SendError as WebdavSendError,
};

use crate::types::{BridgeError, PushOutcome};

/// A completed coroutine failure as the bridge error: the display
/// message, plus the HTTP status dug out of the failure itself.
pub(crate) fn coroutine_error(err: &(impl StdError + 'static)) -> BridgeError {
    BridgeError {
        message: err.to_string(),
        status: http_status(err),
    }
}

/// Walks the failure's source chain down to the transport leaf that
/// knows the HTTP status of the failed round, if the failure was one:
/// io-webdav and io-jmap carry it on their send errors, io-msgraph
/// and io-google-people expose it as an accessor.
fn http_status(err: &(dyn StdError + 'static)) -> Option<u16> {
    let mut cause = Some(err);

    while let Some(err) = cause {
        if let Some(WebdavSendError::HttpStatus(status, _)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(FollowRedirectsError::HttpStatus(status, _)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapSendError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(send) = err.downcast_ref::<MsgraphSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<PeopleSendError>() {
            return send.status();
        }
        cause = err.source();
    }

    None
}

/// A required field of a push change, named in the error when absent.
pub(crate) fn required<'a>(
    field: &'a Option<String>,
    op: &str,
    name: &str,
) -> Result<&'a str, BridgeError> {
    field
        .as_deref()
        .ok_or_else(|| format!("Push {op} change is missing its {name}").into())
}

/// One rejected push outcome carrying what the server objected.
pub(crate) fn rejected(reference: String, error: String) -> PushOutcome {
    PushOutcome {
        reference,
        accepted: false,
        error: Some(error),
        ..Default::default()
    }
}
