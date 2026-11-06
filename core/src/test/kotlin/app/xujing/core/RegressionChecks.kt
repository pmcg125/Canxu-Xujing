package app.xujing.core

import app.xujing.core.engine.MacroCompatibilityChecks
import app.xujing.core.engine.VariableUpdateOrderChecks
import app.xujing.core.engine.MvuLifecycleChecks
import app.xujing.core.engine.MvuReferenceChecks
import app.xujing.core.engine.WorldbookM1Checks

fun main() {
    SummaryChecks.run()
    ImportIntegrityChecks.run()
    PresetSamplingChecks.run()
    SettingsRoundtripChecks.run()
    MacroCompatibilityChecks.run()
    CardLibraryChecks.run()
    CardVariableChecks.run()
    VariableUpdateOrderChecks.run()
    MvuReferenceChecks.run()
    MvuLifecycleChecks.run()
    WorldbookM1Checks.run()
    println("All focused regression checks passed.")
}
