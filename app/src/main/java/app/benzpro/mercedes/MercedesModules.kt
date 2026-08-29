package app.benzpro.mercedes

enum class AddressScheme { Iso11, Iso29 }

data class CanTarget(
    val scheme: AddressScheme,
    val requestHeader: String,
    val receiveAddress: String,
)

data class MbModule(
    val id: String,
    val name: String,
    val expected: Boolean,
    val targets: List<CanTarget>,
)

object MercedesModules {
    val catalog: List<MbModule> = listOf(
        module("cdi", "CDI (OM651)", true, iso11("7E0", "7E8"), iso29("DA10F1", "18DAF110"), iso29("DA00F1", "18DAF100")),
        module("egs52", "EGS52 (722.6)", true, iso11("7E1", "7E9"), iso29("DA18F1", "18DAF118"), iso29("DA02F1", "18DAF102")),
        module("esp", "ESP", true, iso11("7E2", "7EA"), iso29("DA28F1", "18DAF128"), iso29("DA03F1", "18DAF103")),
        module("srs", "SRS / airbag", true, iso29("DAB0F1", "18DAF1B0"), iso29("DA04F1", "18DAF104")),
        module("sam_f", "Front SAM / CGW", true, iso29("DA40F1", "18DAF140"), iso29("DA1FF1", "18DAF11F")),
        module("sam_r", "Rear SAM", true, iso29("DA41F1", "18DAF141")),
        module("ic", "Instrument cluster", true, iso29("DA09F1", "18DAF109"), iso29("DA3FF1", "18DAF13F")),
        module("eis", "EIS", true, iso29("DA05F1", "18DAF105")),
        module("aac", "Climate (AAC)", true, iso29("DA1AF1", "18DAF11A")),
        module("tpm", "TPM", false, iso29("DA60F1", "18DAF160")),
        module("pts", "Parktronic", false, iso29("DA61F1", "18DAF161")),
        module("lwr", "Headlamp L", false, iso29("DA70F1", "18DAF170")),
        module("rwr", "Headlamp R", false, iso29("DA71F1", "18DAF171")),
        module("door_l", "Door left", false, iso29("DA50F1", "18DAF150")),
        module("door_r", "Door right", false, iso29("DA51F1", "18DAF151")),
        module("scm", "Steering column", false, iso29("DA80F1", "18DAF180")),
        module("comand", "Audio / COMAND", false, iso29("DA90F1", "18DAF190")),
        module("epb", "Electric parking brake", false, iso29("DA2BF1", "18DAF12B")),
        module("eps", "EPS", false, iso29("DA12F1", "18DAF112")),
    )

    private fun iso11(req: String, rx: String) = CanTarget(AddressScheme.Iso11, req, rx)
    private fun iso29(req: String, rx: String) = CanTarget(AddressScheme.Iso29, req, rx)

    private fun module(
        id: String,
        name: String,
        expected: Boolean,
        vararg targets: CanTarget,
    ) = MbModule(id, name, expected, targets.toList())
}
