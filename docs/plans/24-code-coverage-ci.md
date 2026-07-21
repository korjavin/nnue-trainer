# Plan: Code Coverage Quality Gates in CI

This plan specifies the integration of Jacoco code coverage reporting and validation gates in the Maven build and GitHub Actions workflow.

## 1. Goal
Ensure that the project's codebase maintains a healthy unit test coverage. We will add the `jacoco-maven-plugin` to fail the build if test line coverage falls below a baseline threshold (e.g. 40%).

## 2. Requirements

### 2.1 Add Jacoco Maven Plugin (`pom.xml`)
- Add the `jacoco-maven-plugin` under the `<plugins>` section in `pom.xml`:
  ```xml
  <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>0.8.12</version>
      <executions>
          <!-- Prepare the agent before running tests -->
          <execution>
              <goals>
                  <goal>prepare-agent</goal>
              </goals>
          </execution>
          <!-- Generate the coverage report after tests -->
          <execution>
              <id>report</id>
              <phase>test</phase>
              <goals>
                  <goal>report</goal>
              </goals>
          </execution>
          <!-- Enforce minimum coverage check -->
          <execution>
              <id>check</id>
              <goals>
                  <goal>check</goal>
              </goals>
              <configuration>
                  <rules>
                      <rule>
                          <element>BUNDLE</element>
                          <limits>
                              <limit>
                                  <counter>LINE</counter>
                                  <value>COVEREDRATIO</value>
                                  <minimum>0.30</minimum> <!-- Enforce 30% line coverage -->
                              </limit>
                          </limits>
                      </rule>
                  </rules>
              </configuration>
          </execution>
      </executions>
  </plugin>
  ```

### 2.2 Verify locally and in CI
- Run spotless formatting check.
- Verify that `./mvnw clean test` generates the coverage report under `target/site/jacoco/index.html` and checks the coverage threshold successfully.
