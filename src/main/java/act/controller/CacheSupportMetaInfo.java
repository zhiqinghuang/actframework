package act.controller;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.app.ActionContext;
import org.osgl.$;

public class CacheSupportMetaInfo {

    public $.Function<ActionContext, String> keyGenerator;
    public boolean enabled;
    public int ttl;
    public boolean supportPost;

    private CacheSupportMetaInfo() {}

    public String cacheKey(ActionContext context) {
        return keyGenerator.apply(context);
    }

    public static CacheSupportMetaInfo disabled() {
        return new CacheSupportMetaInfo();
    }

    public static CacheSupportMetaInfo enabled($.Function<ActionContext, String> keyGenerator, int ttl, boolean supportPost) {
        CacheSupportMetaInfo meta = new CacheSupportMetaInfo();
        meta.enabled = true;
        meta.ttl = ttl;
        meta.supportPost = supportPost;
        meta.keyGenerator = $.notNull(keyGenerator);
        return meta;
    }
}
