package com.hospitality.payment.adapter.in.web;

import com.hospitality.payment.application.PaymentCommands;
import com.hospitality.payment.application.PaymentService;
import com.hospitality.payment.domain.CardReference;
import com.hospitality.payment.domain.Money;
import com.hospitality.payment.domain.Payment;
import com.hospitality.payment.domain.PaymentStatus;
import com.hospitality.payment.adapter.out.persistence.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de paiement.
 *
 * <p><b>Toute operation mutante exige un en-tete {@code Idempotency-Key}.</b> Ce n'est
 * pas une option de confort : un client de paiement <i>va</i> retenter -- timeout,
 * rechargement de page, double clic -- et sans cette cle, chaque retry est un debit
 * supplementaire. On la rend obligatoire au niveau du contrat plutot que de compter sur
 * la discipline des integrateurs.</p>
 *
 * <p>Versionnement par le chemin ({@code /v1}) : le plus explicite et le plus simple a
 * router cote ALB ou CloudFront. Les clients d'une API de paiement sont nombreux et
 * mettent longtemps a migrer -- plusieurs versions doivent pouvoir coexister.</p>
 */
@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Paiements", description = "Autorisation, capture, remboursement et annulation")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository payments;

    public PaymentController(PaymentService paymentService, PaymentRepository payments) {
        this.paymentService = paymentService;
        this.payments = payments;
    }

    @PostMapping
    @Operation(summary = "Autoriser un paiement",
            description = "Fait reserver les fonds chez l'emetteur, sans debit. "
                    + "Renvoie 201 si autorise, 402 si refuse par l'emetteur, "
                    + "202 si un challenge 3DS est requis.")
    public ResponseEntity<PaymentDtos.PaymentResponse> authorize(
            @Parameter(description = "Cle unique generee par l'appelant ; rejouer la meme cle "
                    + "renvoie la reponse d'origine au lieu de creer un second paiement",
                    required = true)
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PaymentDtos.AuthorizeRequest request) {

        PaymentCommands.Authorize command = new PaymentCommands.Authorize(
                request.reservationId(),
                request.hotelId(),
                Money.of(request.amount(), request.currency()),
                new CardReference(request.paymentMethodToken(), request.cardBrand(),
                        request.cardBin(), request.cardLast4(), request.cardExpiry()),
                request.customerPresentOrDefault(),
                request.initialNetworkTransactionId());

        PaymentService.Result result = paymentService.authorize(command, idempotencyKey, canonical(request));
        return respond(result);
    }

    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capturer un paiement",
            description = "Convertit l'autorisation en demande de paiement reelle. "
                    + "Capture partielle autorisee : au check-out, le folio reel est souvent "
                    + "inferieur au montant autorise.")
    public ResponseEntity<PaymentDtos.PaymentResponse> capture(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentDtos.AmountRequest request) {

        PaymentService.Result result = paymentService.capture(
                new PaymentCommands.Capture(paymentId, Money.of(request.amount(), request.currency())),
                idempotencyKey, paymentId + "|" + canonical(request));
        return respond(result);
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Rembourser un paiement",
            description = "Total ou partiel, dans la limite du montant effectivement capture.")
    public ResponseEntity<PaymentDtos.PaymentResponse> refund(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentDtos.AmountRequest request) {

        PaymentService.Result result = paymentService.refund(
                new PaymentCommands.Refund(paymentId, Money.of(request.amount(), request.currency()),
                        request.reason()),
                idempotencyKey, paymentId + "|" + canonical(request));
        return respond(result);
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Annuler une autorisation",
            description = "Libere le hold avant capture. Contrairement a un remboursement, "
                    + "l'operation n'apparait pas sur le releve du client.")
    public ResponseEntity<PaymentDtos.PaymentResponse> cancel(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) PaymentDtos.CancelRequest request) {

        String reason = request == null ? null : request.reason();
        PaymentService.Result result = paymentService.cancel(
                new PaymentCommands.Cancel(paymentId, reason),
                idempotencyKey, paymentId + "|cancel|" + reason);
        return respond(result);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Consulter un paiement")
    public PaymentDtos.PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentDtos.PaymentResponse.from(paymentService.get(paymentId), false);
    }

    @GetMapping
    @Operation(summary = "Lister les paiements d'un hotel",
            description = "Vue du portail hotelier. Le cloisonnement par hotelId est "
                    + "le point de controle multi-tenant : en production il est derive du "
                    + "jeton d'authentification, jamais d'un parametre de requete.")
    public Page<PaymentDtos.PaymentResponse> list(
            @RequestParam String hotelId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<Payment> result = status == null
                ? payments.findByHotelIdOrderByCreatedAtDesc(hotelId, pageable)
                : payments.findByHotelIdAndStatusOrderByCreatedAtDesc(hotelId, status, pageable);
        return result.map(payment -> PaymentDtos.PaymentResponse.from(payment, false));
    }

    @GetMapping("/by-reservation/{reservationId}")
    @Operation(summary = "Paiements d'une reservation",
            description = "Une reservation porte souvent plusieurs paiements : garantie, "
                    + "acompte, extras, solde au check-out.")
    public List<PaymentDtos.PaymentResponse> byReservation(@PathVariable String reservationId) {
        return payments.findByReservationIdOrderByCreatedAtDesc(reservationId).stream()
                .map(payment -> PaymentDtos.PaymentResponse.from(payment, false))
                .toList();
    }

    private ResponseEntity<PaymentDtos.PaymentResponse> respond(PaymentService.Result result) {
        return ResponseEntity.status(result.httpStatus())
                .body(PaymentDtos.PaymentResponse.from(result.payment(), result.replayed()));
    }

    /**
     * Forme canonique de la requete, hachee pour detecter une cle d'idempotence
     * reutilisee avec un corps different.
     *
     * <p>{@code record::toString} suffit ici car un record produit une representation
     * deterministe de ses composants. Sur un DTO avec des collections ou des maps, il
     * faudrait une vraie canonicalisation (tri des cles, JSON normalise) : deux corps
     * JSON equivalents mais formates differemment ne doivent pas produire deux
     * empreintes differentes.</p>
     */
    private String canonical(Object request) {
        return String.valueOf(request);
    }
}
