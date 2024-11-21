package no.nav.dagpenger.andre.ytelser.sykepenger

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.dagpenger.andre.ytelser.sykepenger.modell.Perioder

class SykepengerService(
    rapidsConnection: RapidsConnection,
    private val client: SykepengerClient,
) : River.PacketListener {
    companion object {
        internal object BEHOV {
            const val SYKEPENGER_BEHOV = "Sykepenger"
        }

        private val logger = KotlinLogging.logger {}
        private val sikkerlogg = KotlinLogging.logger("tjenestekall")
    }

    init {
        River(rapidsConnection)
            .apply {
                validate {
                    it.demandAllOrAny("@behov", listOf(BEHOV.SYKEPENGER_BEHOV))
                    it.forbid("@løsning")
                    it.requireKey("ident")
                    it.requireKey(BEHOV.SYKEPENGER_BEHOV)
                    it.interestedIn("søknadId", "@behovId", "behandlingId")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
    ) {
        withLoggingContext(
            "behandlingId" to packet["behandlingId"].asText(),
            "behovId" to packet["@behovId"].asText(),
        ) {
            val ident = packet["ident"].asText()
            val ønsketDato = packet[BEHOV.SYKEPENGER_BEHOV]["Virkningsdato"].asLocalDate()
            val fom = ønsketDato.minusWeeks(8)
            val tom = ønsketDato

            val perioder: Perioder =
                runBlocking(MDCContext()) {
                    client.hentSykepenger(ident, fom, tom)
                }

            val løsning = perioder.utbetaltePerioder.any { ønsketDato in it }

            packet["@løsning"] = mapOf(BEHOV.SYKEPENGER_BEHOV to løsning)

            // Ta med ufiltret respons for å sikre bedre sporing
            packet["@kilde"] =
                mapOf(
                    "navn" to "spøkelse-api",
                    "data" to perioder,
                )

            logger.info { "løser behov '$BEHOV' - Sykepengeperioder: $perioder" }
            context.publish(packet.toJson())
        }
    }
}
