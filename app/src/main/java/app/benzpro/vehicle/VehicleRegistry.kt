package app.benzpro.vehicle

enum class VehicleKind { Car, Bike }

enum class ElmInitKind { MercedesCan, KawasakiKwp }

data class VehicleCapabilities(
    val moduleScan: Boolean,
    val healthStrip: Boolean,
    val freezeFrame: Boolean,
    val readiness: Boolean,
    val mercedesNotes: Boolean,
)

data class VehicleProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val vin: String,
    val kind: VehicleKind,
    val elmInit: ElmInitKind,
    val capabilities: VehicleCapabilities,
    val hint: String,
)

object VehicleRegistry {
    const val E250_ID = "e250_c207"
    const val Z1000_ID = "z1000_abs"

    val e250 = VehicleProfile(
        id = E250_ID,
        displayName = "E250 CDI Coupe",
        subtitle = "C207 · OM651 · 5G",
        vin = "WDD2073031F010216",
        kind = VehicleKind.Car,
        elmInit = ElmInitKind.MercedesCan,
        capabilities = VehicleCapabilities(
            moduleScan = true,
            healthStrip = true,
            freezeFrame = true,
            readiness = true,
            mercedesNotes = true,
        ),
        hint = "2010 C207 E250 CDI · OM651.911 · 5G-Tronic 722.6 / EGS52",
    )

    val z1000 = VehicleProfile(
        id = Z1000_ID,
        displayName = "Z1000 ABS",
        subtitle = "ZRT00B · Euro 3",
        vin = "JKAZRT00BCA020081",
        kind = VehicleKind.Bike,
        elmInit = ElmInitKind.KawasakiKwp,
        capabilities = VehicleCapabilities(
            moduleScan = false,
            healthStrip = false,
            freezeFrame = false,
            readiness = false,
            mercedesNotes = false,
        ),
        hint = "2008 Kawasaki Z1000 ABS · 4-pin KDS K-line · ABS is a separate plug",
    )

    fun all(): List<VehicleProfile> = listOf(e250, z1000)

    fun byId(id: String): VehicleProfile = all().firstOrNull { it.id == id } ?: e250
}
