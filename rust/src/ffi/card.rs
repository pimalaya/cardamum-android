//! Account and card CRUD/sync JNI entry points.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::{from_str, json, to_string};

use crate::{
    account::{self, Backend},
    client::Client,
    ffi::{error_json, parse_books, parse_strings, parse_url, read_string},
    types::{CardDelta, Credentials, PushChange},
};

/// `Native.accountInfo`: the backend behind an account base URL
/// (`carddav`, `graph`, `jmap`, `google`, or `local` for the built-in
/// on-device account) and whether its cards are account-level resources
/// with m:n addressbook memberships; pure computation, no transport.
/// Returns `{"backend": "..", "accountLevel": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_accountInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base_url: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);

        // NOTE: the local account has no transport, so it is not a
        // Backend; reporting it so keeps its cards non-account-level
        // (one book, per-collection keys) and out of backend matches.
        let (backend, account_level) = if base_url.starts_with(account::LOCAL_PREFIX) {
            ("local", false)
        } else {
            let backend = Backend::of(&base_url);
            (backend.name(), backend.account_level())
        };

        let json = json!({
            "backend": backend,
            "accountLevel": account_level,
        })
        .to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.accountBase`: builds an account base URL of the given kind
/// (`googleCarddav`, `google`, `msgraph` from an email, `jmap` from an
/// HTTPS session URL or bare host); pure computation, no transport.
/// Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_accountBase<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    value: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match account::base_url(&kind, &value) {
            Ok(url) => json!({ "url": url }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listAddressbooks`: lists the account's addressbooks, the
/// backend dispatched from the base URL. Returns a JSON array of
/// addressbooks carrying absolute collection URLs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_addressbooks(&base_url, &credentials) {
            Ok(books) => to_string(&books).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listAccountCards`: lists every card of an account-level
/// backend (JMAP, Google) in one pass, each carrying its addressbook
/// memberships as book ids. Returns a JSON array of
/// `{id, uri, etag, vcard, books}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listAccountCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_account_cards(&base_url, &credentials) {
            Ok(cards) => to_string(&cards).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listCards`: lists the cards of the addressbook collection,
/// the backend dispatched from the base URL (CardDAV, Graph). Returns
/// a JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_listCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).list_cards(
            &base_url,
            &addressbook_url,
            &credentials,
        ) {
            Ok(cards) => to_string(&cards).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createCard`: creates the card in the addressbook
/// collection, the backend dispatched from the base URL. Returns the
/// created `{id, uri, etag, vcard}` (the server-assigned id on the
/// backends naming the resource themselves).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).create_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &vcard,
        ) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readCard`: reads the card at the given resource name (as
/// the server returned it), the backend dispatched from the base URL.
/// Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_readCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).read_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &uri,
        ) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateCard`: updates the card at the given resource name
/// (as the server returned it; empty falls back to the id), the
/// backend dispatched from the base URL. The base vCard trims the
/// patching backends' updates to the fields the edit changed and the
/// ETag guards the guarding backends' writes (empty means unknown for
/// both). Returns the updated `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let base = (!base_vcard.is_empty()).then_some(base_vcard.as_str());
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).update_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &uri,
            &vcard,
            base,
            if_match,
        ) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteCard`: deletes the card at the given resource name
/// (as the server returned it; empty falls back to the id), the
/// backend dispatched from the base URL and the ETag guarding the
/// guarding backends' deletion (empty means unknown). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let etag = read_string(env, &etag);
        let if_match = (!etag.is_empty()).then_some(etag.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).delete_card(
            &base_url,
            &addressbook_url,
            &credentials,
            &id,
            &uri,
            if_match,
        ) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createCards`: creates a batch of cards (a JSON array of
/// vCard documents) in the addressbook collection, Google-only (the
/// one backend with a batch create verb). Returns a JSON array of the
/// created `{id, uri, etag, vcard}` in input order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_createCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    vcards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let vcards = read_string(env, &vcards);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_strings(&vcards) {
            Ok(vcards) => {
                match Client::new(env, &transport).create_cards(&base_url, &credentials, &vcards) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteCards`: deletes a batch of cards (a JSON array of
/// card ids) from the addressbook collection, Google-only (the one
/// backend with a batch delete verb). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_deleteCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let ids = read_string(env, &ids);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_strings(&ids) {
            Ok(ids) => {
                match Client::new(env, &transport).delete_cards(&base_url, &credentials, &ids) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.pushCards`: pushes a round of changes (a JSON array of
/// `{ref, op, id?, vcard?, baseVcard?, add?, remove?}`) to the
/// addressbook collection as batch calls, JMAP (ContactCard/set) and
/// Graph ($batch) only. Returns a JSON array of `{ref, accepted, id?,
/// etag?, error?}`, one outcome per change.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_pushCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    changes: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let changes = read_string(env, &changes);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match from_str::<Vec<PushChange>>(&changes) {
            Ok(changes) => {
                match Client::new(env, &transport).push_cards(
                    &base_url,
                    &addressbook_url,
                    &credentials,
                    &changes,
                ) {
                    Ok(outcomes) => {
                        to_string(&outcomes).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(format!("Unreadable push changes: {err}")),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.syncCards`: lists the collection's changes since the given
/// cursor (empty runs the initial round: the complete member set plus
/// the cursor to delta from next time), the backend dispatched from
/// the base URL. An expired cursor re-runs an initial round and an
/// initial CardDAV sync a server rejects falls back to the plain
/// enumeration, both internally. Returns `{"changed": [{id, uri,
/// etag, vcard?, books?}], "vanished": [uri], "token": "..",
/// "complete": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_syncCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sync_token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sync_token = read_string(env, &sync_token);
        let sync_token = (!sync_token.is_empty()).then_some(sync_token.as_str());
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match Client::new(env, &transport).sync_cards(
            &base_url,
            &addressbook_url,
            &credentials,
            sync_token,
        ) {
            Ok(delta) => delta_json(delta),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.multigetCards`: batch-fetches the cards at the given
/// resource names (a JSON string array) inside the addressbook
/// collection via REPORT `addressbook-multiget`. Returns a JSON array
/// of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_multigetCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uris: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uris = read_string(env, &uris);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match (parse_url(&addressbook_url), parse_strings(&uris)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(url), Ok(uris)) => {
                let uris: Vec<&str> = uris.iter().map(String::as_str).collect();
                match Client::new(env, &transport).multiget_cards(&url, &credentials, &uris) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateCardBooks`: adds and removes the card's addressbook
/// memberships on an account-level backend (JSON string arrays of book
/// ids), the backend dispatched from the base URL. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_updateCardBooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    add: JString<'local>,
    remove: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let add = read_string(env, &add);
        let remove = read_string(env, &remove);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match (parse_books(&add), parse_books(&remove)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(add), Ok(remove)) => {
                match Client::new(env, &transport).update_card_books(
                    &base_url,
                    &credentials,
                    &id,
                    &add,
                    &remove,
                ) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Serializes any backend's delta.
fn delta_json(delta: CardDelta) -> String {
    to_string(&delta).unwrap_or_else(|err| error_json(err.to_string()))
}
