//! vCard projection, form and merge JNI entry points.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint},
};
use serde_json::{from_str, json};

use crate::{
    ffi::{error_json, parse_ref_cards, parse_strings, read_string},
    project::{
        apply, card_prop_labels, card_props, card_set_prop, card_set_prop_parts, card_source,
        card_type_order, duplicate_group, find_duplicates, form_date, form_entry, form_view,
        group_contacts, index, merge_cards, merge_conflict, merge_conflict_form, project, set_uid,
    },
};

/// `Native.indexCard`: indexes a vCard for the store (display name,
/// first email and phone, UID, normalized content hash); pure
/// computation, no transport. Returns
/// `{"name", "email", "phone", "uid", "hash"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_indexCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match index(&vcard) {
            Ok(index) => index.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeCards`: merges several vCards (a JSON string array)
/// into one union document with its field model and the per-field
/// alternatives for the merge form (docs/merged-view.md); pure
/// computation, no transport. Returns
/// `{"vcard", "model", "alternatives"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_strings(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match merge_cards(&cards) {
                Ok(merged) => merged.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeCardChanges`: three-way merges a conflicted push (the
/// staged local edit and the fetched remote card against their common
/// base; the local side wins same-field collisions); pure computation,
/// no transport. Returns `{"vcard", "conflicts"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeCardChanges<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict(&base, &local, &remote) {
            Ok(merged) => merged.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeConflictForm`: builds the conflict form's inputs for a
/// both-sides-edited row (the newer side by REV wins collisions as the
/// pre-filled default, both candidates offered per field); pure
/// computation, no transport. Returns `{"vcard", "model", "alternatives",
/// "changed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_mergeConflictForm<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict_form(&base, &local, &remote) {
            Ok(form) => form.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.setCardUid`: rewrites the card's UID (a plain copy is a new
/// identity); pure computation, no transport. Returns
/// `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_setCardUid<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    uid: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let uid = read_string(env, &uid);

        let json = match set_uid(&vcard, &uid) {
            Ok(fresh) => json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.findDuplicates`: finds groups of likely-duplicate cards
/// (exact normalized email, phone or name matches) for the duplicate
/// remover; pure computation, no transport. Takes a JSON array of
/// `{"ref", "vcard"}` pairs, returns
/// `{"groups": [{"refs": [...], "reasons": [...]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_findDuplicates<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_ref_cards(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match find_duplicates(&cards) {
                Ok(found) => found.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardProps`: lists the card's raw property lines for the
/// advanced editor; pure computation, no transport. Returns
/// `{"props": ["VERSION:4.0", ...]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardProps<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match card_props(&vcard) {
            Ok(props) => json!({ "props": props }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSetProp`: rewrites one raw property line for the
/// advanced editor (a blank line removes, index -1 appends); pure
/// computation, no transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSetProp<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    index: jint,
    line: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let line = read_string(env, &line);

        let json = match card_set_prop(&vcard, index as i64, &line) {
            Ok(fresh) => json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSetPropParts`: recomposes one property from its
/// structured parts (`{"name", "params": [{"name", "values"}],
/// "value"}`) and rewrites it (index -1 appends); pure computation, no
/// transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSetPropParts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    index: jint,
    prop: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let prop = read_string(env, &prop);

        let json = match from_str(&prop) {
            Err(err) => error_json(format!("Invalid property: {err}")),
            Ok(prop) => match card_set_prop_parts(&vcard, index as i64, &prop) {
                Ok(fresh) => json!({ "vcard": fresh }).to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardPropLabels`: the component labels of a structured
/// property name (N, ADR, GENDER; empty for plain values), shaping the
/// advanced editor's value form; pure computation, no transport.
/// Returns `{"labels": [...]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardPropLabels<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let name = read_string(env, &name);
        let json = json!({ "labels": card_prop_labels(&name) }).to_string();
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardTypeOrder`: the ordered type-set vocabulary the edit
/// form's spinners address for the kind (`phone`, `email`, `address`,
/// `relation`, `gender`), each position's vCard TYPE set in the order
/// the Android string-arrays must mirror; pure computation, no
/// transport. Returns `{"order": [[..], ..]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardTypeOrder<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let json = json!({ "order": card_type_order(&kind) }).to_string();
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.cardSource`: validates a hand-edited vCard source (it must
/// reparse) and returns it re-serialized; pure computation, no
/// transport. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_cardSource<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match card_source(&vcard) {
            Ok(fresh) => json!({ "vcard": fresh }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.projectCard`: projects a vCard onto the neutral field model
/// the app maps to ContactsContract rows (docs/contacts-mapping.md).
/// Returns the model JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_projectCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match project(&vcard) {
            Ok(model) => model.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.applyCard`: patches the edited field model back onto the
/// vCard, preserving every unmanaged property (docs/contacts-mapping.md).
/// Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_applyCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let model = read_string(env, &model);

        let json = match from_str(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => match apply(&vcard, &model) {
                Ok(patched) => json!({ "vcard": patched }).to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formView`: the edit form's view support computed from the
/// field model (summaries, type spinner positions, picker dates); pure
/// computation, no transport. Returns `{"name", "organization",
/// "gender"?, "birthday"?, "anniversary"?, "phones", "emails",
/// "relations", "addresses"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formView<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let model = read_string(env, &model);

        let json = match from_str(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => form_view(&model).to_string(),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formEntry`: one typed entry saved from an edit dialog, its
/// TYPE set drawn from the spinner position (`phone`, `email`,
/// `relation` return the full entry, `address` the TYPE set alone,
/// `gender` the GENDER object, empty when unset); pure computation, no
/// transport.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formEntry<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    index: jint,
    value: JString<'local>,
    pref: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match form_entry(&kind, index as i64, &value, pref) {
            Ok(entry) => entry.to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.formDate`: one picked date on the model wire (the vCard
/// `yyyy-mm-dd` form, 1-based month); pure computation, no transport.
/// Returns `{"value": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_formDate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    year: jint,
    month: jint,
    day: jint,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let value = form_date(year as i64, month as i64, day as i64);
        let json = json!({ "value": value }).to_string();

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.groupContacts`: groups the replica pool into merged
/// contacts (docs/merged-view.md), the groups sorted by primary
/// display name; pure computation, no transport. Takes `{"replicas":
/// [{ref, uid, name, id}], "links": {member: cluster}, "detached":
/// [ref]}`, returns `{"groups": [{key, replicas: [index]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_groupContacts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let input = read_string(env, &input);

        let json = match from_str(&input) {
            Err(err) => error_json(format!("Invalid replica pool: {err}")),
            Ok(input) => match group_contacts(&input) {
                Ok(groups) => groups.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.duplicateGroup`: the duplicate review's group facts, the
/// dismissal key and the Link eligibility; pure computation, no
/// transport. Takes `[{ref, book}]`, returns `{"key", "linkable"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_duplicateGroup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    members: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let members = read_string(env, &members);

        let json = match from_str(&members) {
            Err(err) => error_json(format!("Invalid duplicate group: {err}")),
            Ok(members) => match duplicate_group(&members) {
                Ok(facts) => facts.to_string(),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}
