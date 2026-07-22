#!/bin/bash
./mvnw exec:java -Dexec.mainClass="com.engine.nnue_trainer.train.SearchAB" -Dexec.classpathScope=runtime
