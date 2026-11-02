/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

/** Stand-in for `rtabmap::Rtabmap`: the calls the bridge makes, and nothing else. */
#pragma once

#include "Parameters.h"
#include "SensorData.h"

namespace rtabmap {

class Rtabmap {
 public:
  void setParameters(const ParametersMap& parameters) { (void)parameters; }
  void process(const SensorData& data) { (void)data; }
  int getLoopClosureId() const { return 0; }
  void setOptimized() {}
  void close() {}
};

}  // namespace rtabmap
