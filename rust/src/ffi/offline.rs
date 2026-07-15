//! Offline-engine and store-decision JNI entry points.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
    sys::jboolean,
};
use serde_json::{Value, json};

use crate::{
    ffi::{error_json, parse_facts, parse_strings, read_string},
    offline, store,
};

/// `Native.offlineSync`: reconciles the collection with its remote
/// through the io-offline engine, servicing every engine yield via the
/// given `OfflineDriver`. With `full` the checkpoint is ignored and the
/// whole remote is enumerated. Returns the sync report
/// `{"pulled", "pushed", "conflicts", "rejected", "refreshed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineSync<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    full: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);

        let json = match offline::sync(env, &driver, &collection, full) {
            Ok(report) => report.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineUpgrade`: raises the given handles (a JSON string
/// array) to the full detail tier through the io-offline engine,
/// servicing every engine yield via the given `OfflineDriver`. Returns
/// the upgrade report `{"upgraded", "fetched", "deduped"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineUpgrade<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    handles: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let handles = read_string(env, &handles);

        let json = match parse_strings(&handles) {
            Err(err) => error_json(err),
            Ok(handles) => match offline::upgrade(env, &driver, &collection, handles) {
                Ok(report) => report.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineMutate`: stages a local mutation (a JSON object,
/// e.g. `{"op": "edit", "handle", "hash", "size", "body", "meta"}`)
/// through the io-offline engine, servicing the storage yields via the
/// given `OfflineDriver`; the remote is never touched. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineMutate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    mutation: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let mutation = read_string(env, &mutation);

        let json = match offline::mutate(env, &driver, &collection, &mutation) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineRetryUnguarded`: whether a 412-rejected push may
/// retry unguarded, the last enumerate proving the handle unchanged
/// (the CardDAV If-Match quirk); pure computation, no transport. Takes
/// `{"listed": {handle: etag}?, "complete", "handle", "ifMatch"?}`,
/// returns `{"retry": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineRetryUnguarded<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => json!({ "retry": store::retry_unguarded(&facts) }).to_string(),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineAccountSnapshot`: projects an account-wide delta
/// (JMAP, Google) onto one book's enumerate; pure computation, no
/// transport. Takes `{"bookId"?, "complete", "changed": [{handle,
/// books, known}], "vanished"}`, returns `{"members": [index],
/// "vanished": [handle]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineAccountSnapshot<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::account_snapshot(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePushPlan`: plans one push change (membership patch
/// vs create or delete, plus the Google post-create membership); pure
/// computation, no transport. Takes `{"op", "collection", "bookId"?,
/// "origin", "deleted"}`, returns `{"action", "postCreateBooks"?}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePushPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::push_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePlacement`: maps one card-plus-membership row to its
/// engine placement on the server axis; pure computation, no
/// transport. Takes the row facts (see the store module), returns
/// `{"placement": {..} | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePlacement<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => placement_json(store::placement(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhonePlacement`: maps one card-plus-membership row
/// to its phone-axis placement; pure computation, no transport. Takes
/// the row facts (see the store module), returns
/// `{"placement": {..} | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhonePlacement<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => placement_json(store::phone_placement(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlineUpsertPlan`: plans one engine upsert onto the card
/// and membership rows; pure computation, no transport. Takes the
/// placement and row facts (see the store module), returns
/// `{"action", "row"?, "memberState"?}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlineUpsertPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::upsert_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhoneUpsertPlan`: plans one phone-axis upsert; pure
/// computation, no transport. Takes the placement and row facts (see
/// the store module), returns `{"action", "row"?, "axis"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhoneUpsertPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::phone_upsert_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.offlinePhoneDropPlan`: plans a phone-collection drop
/// (membership removal vs card deletion); pure computation, no
/// transport. Takes `{"collection", "deleted", "otherMemberships"}`,
/// returns `{"action"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_offlinePhoneDropPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(store::phone_drop_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Serializes a store decision, or its error.
fn decision_json(decision: Result<Value, String>) -> String {
    match decision {
        Ok(value) => value.to_string(),
        Err(err) => error_json(err),
    }
}

/// Serializes a placement decision (`{"placement": {..} | null}`), or
/// its error.
fn placement_json(decision: Result<Option<Value>, String>) -> String {
    match decision {
        Ok(placement) => json!({ "placement": placement.unwrap_or(Value::Null) }).to_string(),
        Err(err) => error_json(err),
    }
}
