package com.aktcl.aron.backend.analytics.mdm

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role

/** What a caller may do in the MDM section (docs/34 s1: module `mdm`, `r` lists and details, `w` also acts). */
enum class MdmLevel { NONE, READ, WRITE }

/**
 * The `mdm` module grant of a caller. backend-authz owns module grants (token claim `modules`); until that lands the
 * interim source is [RoleGrants] (see DECISIONS D-MDM-GRANTS). Swap the instance in the wiring, nothing else changes.
 */
fun interface MdmGrants { fun level(p: AronPrincipal): MdmLevel }

/** Interim: SUPERADMIN and ADMIN write (ADMIN manages devices today, docs/24 s8.5); nobody else, SUPPORT and ANALYST included. */
object RoleGrants : MdmGrants {
    override fun level(p: AronPrincipal) = when (p.role) { Role.SUPERADMIN, Role.ADMIN -> MdmLevel.WRITE; else -> MdmLevel.NONE }
}

internal fun MdmGrants.read(p: AronPrincipal) { if (level(p) == MdmLevel.NONE) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the MDM section needs the mdm module") }
internal fun MdmGrants.write(p: AronPrincipal) { if (level(p) != MdmLevel.WRITE) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "changing MDM needs mdm write") }
