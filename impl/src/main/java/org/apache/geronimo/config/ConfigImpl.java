/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geronimo.config;

import org.apache.geronimo.config.converters.ImplicitArrayConverter;
import org.apache.geronimo.config.converters.MicroProfileTypedConverter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;

import javax.enterprise.inject.Typed;
import javax.enterprise.inject.Vetoed;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.geronimo.config.converters.ImplicitConverter.getImplicitConverter;

/**
 * @author <a href="mailto:struberg@apache.org">Mark Struberg</a>
 * @author <a href="mailto:johndament@apache.org">John D. Ament</a>
 */
@Typed
@Vetoed
public class ConfigImpl implements Config {
    protected Logger logger = Logger.getLogger(ConfigImpl.class.getName());

    protected final List<ConfigSource> configSources = new ArrayList<>();
    protected final ConcurrentMap<Type, MicroProfileTypedConverter> converters = new ConcurrentHashMap<>();
    private static final String ARRAY_SEPARATOR_REGEX = "(?<!\\\\)" + Pattern.quote(",");
    private final ImplicitArrayConverter implicitArrayConverter = new ImplicitArrayConverter(this);

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> asType) {
        String value = getValue(propertyName);
        if (value != null && value.length() == 0) {
            // treat an empty string as not existing
            value = null;
        }
        return Optional.ofNullable(convert(value, asType));
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        String value = getValue(propertyName);
        if (value == null) {
            throw new NoSuchElementException("No configured value found for config key " + propertyName);
        }

        return convert(value, propertyType);
    }

    public String getValue(String key) {
        for (ConfigSource configSource : configSources) {
            String value = configSource.getValue(key);

            if (value != null) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "found value {0} for key {1} in ConfigSource {2}.",
                            new Object[]{value, key, configSource.getName()});
                }

                return value;
            }
        }

        return null;
    }

    public <T> T convert(String value, Class<T> asType) {
        if (value != null) {
            return getConverter(asType).convert(value);
        }
        return null;
    }

    public <T> List<T> convertList(String rawValue, Class<T> arrayElementType) {
        MicroProfileTypedConverter<T> converter = getConverter(arrayElementType);
        String[] parts = rawValue.split(ARRAY_SEPARATOR_REGEX);
        if(parts.length == 0) {
            return Collections.emptyList();
        }
        List<T> elements = new ArrayList<>(parts.length);
        for (String part : parts) {
            part = part.replace("\\,", ",");
            T converted = converter.convert(part);
            elements.add(converted);
        }
        return elements;
    }

    private <T> MicroProfileTypedConverter<T> getConverter(Class<T> asType) {
        MicroProfileTypedConverter<T> microProfileTypedConverter = converters.computeIfAbsent(asType, a -> handleMissingConverter(asType));
        if (microProfileTypedConverter == null) {
            throw new IllegalArgumentException("No Converter registered for class " + asType);
        }

        return microProfileTypedConverter;
    }

    private <T> MicroProfileTypedConverter<T> handleMissingConverter(final Class<T> asType) {
        if(asType.isArray()) {
            return new MicroProfileTypedConverter<T>(value -> (T)implicitArrayConverter.convert(value, asType));
        } else {
            return getImplicitConverter(asType);
        }
    }

    public ConfigValueImpl<String> access(String key) {
        return new ConfigValueImpl<>(this, key);
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return configSources.stream().flatMap(c -> c.getPropertyNames().stream()).collect(Collectors.toSet());
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return Collections.unmodifiableList(configSources);
    }

    public synchronized void addConfigSources(List<ConfigSource> configSourcesToAdd) {
        List<ConfigSource> allConfigSources = new ArrayList<>(configSources);
        allConfigSources.addAll(configSourcesToAdd);

        // finally put all the configSources back into the map
        synchronized (configSources) {
            configSources.clear();
            configSources.addAll(sortDescending(allConfigSources));
        }
    }

    public Map<Type, MicroProfileTypedConverter> getConverters() {
        return converters;
    }

    private List<ConfigSource> sortDescending(List<ConfigSource> configSources) {
        configSources.sort(
                (configSource1, configSource2) -> (configSource1.getOrdinal() > configSource2.getOrdinal()) ? -1 : 1);
        return configSources;

    }

    public void addConverter(Type type, MicroProfileTypedConverter<?> converter) {
        converters.put(type, converter);
    }
}