package unjust.utils.inlining

import monocle.Optional
import unjust.EOObj
import unjust.astparams.EOExprOnly

object types {

  type PathToCallSite = Optional[
    EOObj[EOExprOnly], // method body
    EOObj[EOExprOnly], // call site object
  ]

  type PathToCall = Optional[
    EOObj[EOExprOnly], // call site
    EOExprOnly, // method call
  ]

}
