package com.payangar.bucheron;

import com.payangar.bucheron.platform.Services;

public class BucheronInit {

    public static void init() {
        Constants.LOG.info("Bucheron initializing on {}.", Services.PLATFORM.getPlatformName());
    }
}
