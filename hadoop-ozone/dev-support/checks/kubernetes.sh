#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -u -o pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR/../../.." || exit 1

: ${KUBECONFIG:=/etc/rancher/k3s/k3s.yaml}

export KUBECONFIG

source "${DIR}/_lib.sh"

install_flekszible
install_virtualenv
install_robot
if [[ "$(uname -s)" = "Darwin" ]]; then
  echo "Skip installing k3s, not supported on Mac.  Make sure a working Kubernetes cluster is available." >&2
else
  install_k3s
fi

REPORT_DIR=${OUTPUT_DIR:-"$DIR/../../../target/kubernetes"}

OZONE_VERSION=$(mvn help:evaluate -Dexpression=ozone.version -q -DforceStdout)
DIST_DIR="$DIR/../../dist/target/ozone-$OZONE_VERSION"

if [ ! -d "$DIST_DIR" ]; then
    echo "Distribution dir is missing. Doing a full build"
    "$DIR/build.sh" -Pcoverage
fi

mkdir -p "$REPORT_DIR"

cd "$DIST_DIR/kubernetes/examples" || exit 1
./test-all.sh 2>&1 | tee "${REPORT_DIR}/output.log"
RES=$?
cp -r result/* "$REPORT_DIR/"
cp "$REPORT_DIR/log.html" "$REPORT_DIR/summary.html"
exit $RES
