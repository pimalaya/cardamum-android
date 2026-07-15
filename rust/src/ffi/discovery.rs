//! Discovery and provider-search JNI entry points.

use std::collections::BTreeSet;

use io_pim_discovery::compose::{
    collect::ConfigCollector,
    types::{Service, ServiceConfig},
};
use jni::{
    Env, EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::{from_str, json, to_string};

use crate::{
    client::Client,
    ffi::{error_json, read_string},
};

/// `Native.discover`: resolves the email's domain to a CardDAV context
/// root via RFC 6764, using the given DNS resolver (`tcp://host:port`
/// or an RFC 8484 `https://…/dns-query` URL; empty or null falls back
/// to a public DNS-over-HTTPS one). Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_discover<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let email = read_string(env, &email);
        let resolver = read_string(env, &resolver);
        let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

        let json = match Client::new(env, &transport).discover(&email, resolver) {
            Ok(url) => json!({ "url": url.as_str() }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchProvider`: matches the email's MX records against
/// the fixed provider rules. Returns a JSON array of service configs,
/// empty when no rule matched.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchProvider<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(
            env,
            &transport,
            &email,
            &resolver,
            SearchMechanism::Provider,
        );
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchPacc`: discovers the email domain's PACC document.
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchPacc<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Pacc);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchCarddav`: resolves the email domain's CardDAV context
/// root (RFC 6764). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchCarddav<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json =
            search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Carddav);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchJmap`: resolves the email domain's JMAP session URL
/// (RFC 8620). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchJmap<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Jmap);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchMerge`: pure reduction of per-mechanism config lists
/// (a JSON array of arrays, in mechanism-priority order) into one
/// deduplicated list, restricted to the services the app drives
/// (CardDAV, JMAP). Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchMerge<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    lists: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match search_merge(&read_string(env, &lists)) {
            Ok(json) => json,
            Err(err) => error_json(err),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchProbe`: probes one config's endpoints for their
/// advertised authentication schemes (unauthenticated 401) and
/// refines its password and bearer methods. Returns the (possibly
/// refined) config as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_cardamum_client_Native_searchProbe<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    config: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = read_string(env, &config);

        let json = match from_str(&raw) {
            Err(err) => error_json(format!("Invalid service config: {err}")),
            Ok(config) => match Client::new(env, &transport).search_probe(config) {
                Ok(config) => to_string(&config).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// One discovery mechanism the search verbs dispatch on.
enum SearchMechanism {
    Provider,
    Pacc,
    Carddav,
    Jmap,
}

/// Runs one discovery mechanism and serializes its service configs.
fn search_mechanism_json<'local>(
    env: &mut Env<'local>,
    transport: &JObject<'local>,
    email: &JString<'local>,
    resolver: &JString<'local>,
    mechanism: SearchMechanism,
) -> String {
    let email = read_string(env, email);
    let resolver = read_string(env, resolver);
    let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

    let mut client = Client::new(env, transport);
    let configs = match mechanism {
        SearchMechanism::Provider => client.search_provider(&email, resolver),
        SearchMechanism::Pacc => client.search_pacc(&email, resolver),
        SearchMechanism::Carddav => client.search_carddav(&email, resolver),
        SearchMechanism::Jmap => client.search_jmap(&email, resolver),
    };

    match configs {
        Ok(configs) => to_string(&configs).unwrap_or_else(|err| error_json(err.to_string())),
        Err(err) => error_json(err),
    }
}

/// Merges per-mechanism config lists (in priority order) through
/// io-pim-discovery's pure collector, restricted to the services the
/// app drives (CardDAV, JMAP).
fn search_merge(lists: &str) -> Result<String, String> {
    let lists: Vec<Vec<ServiceConfig>> =
        from_str(lists).map_err(|err| format!("Invalid config lists: {err}"))?;

    let services = BTreeSet::from([Service::Carddav, Service::Jmap]);
    let mut collector = ConfigCollector::new(services);

    for configs in lists {
        collector.collect(configs);
    }

    to_string(&collector.finish()).map_err(|err| err.to_string())
}
