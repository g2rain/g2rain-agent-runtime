package com.g2rain.agent.runtime.client;

import com.g2rain.infra.api.I18nMessageApi;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "g2rain-infra", contextId = "runtimeMessageClient", path = "/i18n_message")
public interface RuntimeMessageClient extends I18nMessageApi {

}
