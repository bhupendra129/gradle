/*
 * Copyright 2017 the original author or authors.
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

import org.gradle.api.Action;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.transform.TransformSpec;

import java.util.Set;

/**
 * A registry for artifact transforms.
 *
 * @implSpec Names should be unique within a project and should follow standard Gradle identifier conventions.  User-provided
 *  names must <strong>not</strong> contain the string {@code unnamedTransform}.
 */
public interface VariantTransformRegistry {
    /**
     * Register an artifact transform, using an implicit name for error identification and reporting.
     *
     * @param actionType the type of the transform action
     * @param registrationAction an action used to configure the transform
     *
     * @see TransformAction
     */
    <T extends TransformParameters> void registerTransform(Class<? extends TransformAction<T>> actionType, Action<? super TransformSpec<T>> registrationAction);

    /**
     * Register an artifact transform with a user-supplied name for error identification and reporting.
     * <p>
     * Transform names must be unique within a project and should follow standard Gradle identifier conventions.  They
     * must <strong>not</strong> contain the string {@code unnamedTransform}.
     * @see TransformAction
     *
     * @param name the name of the transform, used for error identification and reporting
     * @param actionType the type of the transform action
     * @param registrationAction an action used to configure the transform
     */
    <T extends TransformParameters> void registerTransform(String name, Class<? extends TransformAction<T>> actionType, Action<? super TransformSpec<T>> registrationAction);

    /**
     * Returns a set of all the registered transforms.
     *
     * @return the set of registered transforms
     */
    Set<TransformRegistration> getRegistrations();
}
