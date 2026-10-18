// Add to a fork of n0-computer/iroh-ffi as `src/address_lookup.rs`.
// See ../README.md in this directory for the full procedure.

use std::sync::{Arc, Mutex};

use iroh::address_lookup::AddrFilter;
use iroh_mainline_address_lookup::{Builder, DhtAddressLookup as InnerLookup};

use crate::IrohError;

/// Address lookup backed by pkarr records on the BitTorrent Mainline DHT.
///
/// This wraps the *builder* rather than a built lookup, deliberately.
/// `Builder::build` opens a UDP socket that has to be registered with the Tokio
/// reactor, so it must run inside the runtime. That is exactly what iroh does
/// when it calls [`AddressLookupBuilder::into_address_lookup`] during `bind()`.
/// A plain synchronous UniFFI constructor runs on whatever foreign thread
/// called it, with no runtime in scope, and would fail.
///
/// Deferring has a second benefit: iroh hands the endpoint's own secret key to
/// `into_address_lookup`, so callers never have to pass it separately.
#[derive(uniffi::Object)]
pub struct DhtAddressLookup {
    builder: Mutex<Option<Builder>>,
}

#[uniffi::export]
impl DhtAddressLookup {
    /// Configure a Mainline DHT address lookup.
    ///
    /// `publish` announces this endpoint's address to the DHT. Hosts want this.
    /// Joining clients should pass `false`, so they resolve without adding
    /// pointless write traffic to the DHT.
    ///
    /// `unfiltered` also publishes direct IP addresses. The default publishes
    /// only relay addresses, which is the right choice unless the endpoint is
    /// reachable on a public IP.
    #[uniffi::constructor]
    pub fn new(publish: bool, unfiltered: bool) -> Arc<Self> {
        let mut builder = InnerLookup::builder();
        if !publish {
            builder = builder.no_publish();
        }
        if unfiltered {
            builder = builder.addr_filter(AddrFilter::unfiltered());
        }
        Arc::new(Self {
            builder: Mutex::new(Some(builder)),
        })
    }
}

impl DhtAddressLookup {
    /// Hand the builder to an endpoint. Consuming, because an endpoint takes
    /// ownership of its address lookup.
    pub(crate) fn take(&self) -> Result<Builder, IrohError> {
        self.builder
            .lock()
            .unwrap()
            .take()
            .ok_or_else(|| {
                anyhow::anyhow!("this DhtAddressLookup was already used to build an endpoint").into()
            })
    }
}
