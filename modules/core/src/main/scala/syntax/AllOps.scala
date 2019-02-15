// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.syntax

trait ToAllOps
  extends ToIdOps
     with ToStringContextOps

object all extends ToAllOps
