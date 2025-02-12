/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.tooling.events.problems.internal;

import org.gradle.tooling.events.problems.ProblemGroup;
import org.gradle.tooling.events.problems.ProblemId;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;

@NullMarked
public class DefaultProblemId implements ProblemId {

    private final String name;
    private final String displayName;
    private final ProblemGroup group;

    public DefaultProblemId(String name, String displayName, ProblemGroup group) {
        this.name = name;
        this.displayName = displayName;
        this.group = group;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public ProblemGroup getGroup() {
        return group;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultProblemId)) {
            return false;
        }
        DefaultProblemId that = (DefaultProblemId) o;
        return Objects.equals(name, that.name) && Objects.equals(displayName, that.displayName) && Objects.equals(group, that.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, displayName, group);
    }
}
