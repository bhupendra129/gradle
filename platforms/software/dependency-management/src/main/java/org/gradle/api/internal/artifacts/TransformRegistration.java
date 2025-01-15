/*
 * Copyright 2019 the original author or authors.
 *
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
 */

package org.gradle.api.internal.artifacts;

import org.gradle.api.Describable;
import org.gradle.api.internal.artifacts.transform.TransformStep;
import org.gradle.api.internal.attributes.ImmutableAttributes;

/**
 * Registration of an artifact transform.
 */
public interface TransformRegistration extends Describable {
    /**
     * Name of the transform.
     */
    String getName();

    /**
     * Whether a name was explicitly provided when registering this transform.
     *
     * @return {@code true} if the name was <strong>NOT</strong> provided and was generated automatically;
     *  {@code false} if this transform was explicitly named
     */
    boolean isAutomaticallyNamed();

    /**
     * Attributes that match the variant that is consumed.
     */
    ImmutableAttributes getFrom();

    /**
     * Attributes that match the variant that is produced.
     */
    ImmutableAttributes getTo();

    /**
     * Transform step for artifacts of the variant.
     */
    TransformStep getTransformStep();

    @Override
    default String getDisplayName() {
        if (isAutomaticallyNamed()) {
            return "Unnamed transform";
        } else {
            return "Transform '" + getName() + "'";
        }
    }
}
