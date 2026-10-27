package com.tokengate.metering.application.port;

import com.tokengate.metering.domain.PriceVersion;

import java.util.List;

public interface PriceCatalog {

    List<PriceVersion> all();

    void append(PriceVersion version);
}
