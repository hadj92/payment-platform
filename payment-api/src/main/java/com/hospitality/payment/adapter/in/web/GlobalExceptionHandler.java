package com.hospitality.payment.adapter.in.web;

import com.hospitality.payment.domain.PaymentException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Traduction des erreurs du domaine en reponses HTTP, au format <b>ProblemDetail</b>
 * (RFC 9457, ex-7807).
 *
 * <p>Pourquoi ce format : un client d'API de paiement doit pouvoir decider
 * automatiquement s'il retente ou non. Un format standard avec un {@code type} et un
 * {@code code} stables permet ce branchement ; un message libre oblige chaque
 * integrateur a parser du texte.</p>
 *
 * <p><b>Le choix des codes HTTP est la partie qui compte :</b></p>
 * <table border="1">
 *   <tr><th>Situation</th><th>Code</th><th>Pourquoi</th></tr>
 *   <tr><td>Refus de l'emetteur</td><td><b>402</b></td>
 *       <td>La requete est valide, c'est la banque qui refuse. Ni une erreur client
 *           (400), ni une erreur serveur (500) : retenter a l'identique ne sert a rien.</td></tr>
 *   <tr><td>Transition d'etat interdite</td><td><b>409</b></td>
 *       <td>Conflit avec l'etat courant de la ressource : capturer un paiement deja annule.</td></tr>
 *   <tr><td>Requete concurrente en cours</td><td><b>409</b></td>
 *       <td>Meme cle d'idempotence encore en traitement : il faut attendre, pas doubler.</td></tr>
 *   <tr><td>Montant superieur au disponible</td><td><b>422</b></td>
 *       <td>Syntaxe valide, regle metier violee.</td></tr>
 *   <tr><td>Cle d'idempotence recyclee</td><td><b>422</b></td>
 *       <td>Erreur d'integration de l'appelant, a corriger chez lui.</td></tr>
 *   <tr><td>PSP indisponible</td><td><b>503</b></td>
 *       <td>Panne temporaire en aval : rejouable, et {@code Retry-After} le dit explicitement.</td></tr>
 *   <tr><td>Ecriture concurrente perdue</td><td><b>409</b></td>
 *       <td>Verrou optimiste : deux captures simultanees, la seconde doit relire et decider.</td></tr>
 * </table>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://api.example.com/problems/";

    @ExceptionHandler(PaymentException.Declined.class)
    public ProblemDetail onDeclined(PaymentException.Declined e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, e.code(), e.getMessage(), request);
        problem.setProperty("issuerCode", e.pspCode());
        // Information cruciale pour l'appelant : inutile de retenter a l'identique,
        // c'est une decision de l'emetteur, pas une panne.
        problem.setProperty("retryable", false);
        return problem;
    }

    @ExceptionHandler(PaymentException.NotFound.class)
    public ProblemDetail onNotFound(PaymentException.NotFound e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(PaymentException.InvalidState.class)
    public ProblemDetail onInvalidState(PaymentException.InvalidState e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(PaymentException.RequestInProgress.class)
    public ProblemDetail onInProgress(PaymentException.RequestInProgress e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, e.code(), e.getMessage(), request);
        problem.setProperty("retryable", true);
        problem.setProperty("retryAfterSeconds", 2);
        return problem;
    }

    @ExceptionHandler({PaymentException.AmountExceeded.class, PaymentException.IdempotencyConflict.class})
    public ProblemDetail onUnprocessable(PaymentException e, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(PaymentException.PspUnavailable.class)
    public ProblemDetail onPspUnavailable(PaymentException.PspUnavailable e, HttpServletRequest request) {
        log.error("Prestataire de paiement indisponible : {}", e.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, e.code(),
                "Le service de paiement est momentanement indisponible, reessayez avec la meme "
                        + "cle d'idempotence", request);
        problem.setProperty("retryable", true);
        problem.setProperty("retryAfterSeconds", 5);
        return problem;
    }

    /**
     * Verrou optimiste perdu : une autre ecriture a modifie le paiement entre notre
     * lecture et notre ecriture. On renvoie 409 plutot que 500 -- ce n'est pas un bug,
     * c'est le mecanisme qui fonctionne et qui vient d'eviter un double debit.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockingFailureException e, HttpServletRequest request) {
        log.warn("Conflit de verrou optimiste : {}", e.getMessage());
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "concurrent_modification",
                "Le paiement a ete modifie par une autre operation, relisez son etat avant de "
                        + "reessayer", request);
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_failed",
                "La requete contient des champs invalides", request);
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * Depuis Spring 6.1, des qu'un parametre de methode porte une contrainte -- ici le
     * {@code @Size} sur l'en-tete {@code Idempotency-Key} -- la validation du corps
     * remonte sous forme de {@code HandlerMethodValidationException} et non plus de
     * {@code MethodArgumentNotValidException}. Sans ce handler, une simple erreur de
     * saisie repondait 500 au lieu de 400 : c'est un test d'integration qui l'a
     * revele, pas la relecture du code.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail onHandlerValidation(HandlerMethodValidationException e,
                                             HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getAllValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error ->
                        errors.put(result.getMethodParameter().getParameterName() == null
                                        ? "request" : result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_failed",
                "La requete contient des champs invalides", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "missing_header",
                "En-tete obligatoire absent : " + e.getHeaderName()
                        + ". Toute operation mutante exige une cle d'idempotence.", request);
    }

    /**
     * Filet de securite. Le message de l'exception n'est <b>jamais</b> renvoye au
     * client : une stack trace ou un message d'erreur SQL expose la structure interne
     * et, sur un service de paiement, potentiellement des donnees. Le detail reste dans
     * les logs, correle par {@code traceId}.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        log.error("Erreur inattendue sur {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Une erreur interne est survenue", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE_TYPE + code));
        problem.setTitle(code);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
