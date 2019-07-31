#!/usr/bin/env bash

jdeps -cp 'target/libs/*' -s -recursive target/test-1.0-SNAPSHOT.jar | grep target | awk '{print $3}' | xargs ls -la | awk '{print $5, $9}'