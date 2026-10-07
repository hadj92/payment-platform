-- =============================================================================
-- V1 : schema initial de la plateforme de paiement
--
-- Choix de types volontaires :
--   * les montants sont des BIGINT en unites mineures (centimes), jamais des FLOAT.
--     Un flottant introduit des erreurs d'arrondi qui deviennent des ecarts de
--     reconciliation, et la Finance les retrouve toujours.
--   * les horodatages sont en TIMESTAMP UTC. Un systeme de paiement multi-pays ne
--     stocke jamais d'heure locale : on convertit a l'affichage, pas en base.
--   * aucune colonne ne peut contenir un PAN ou un CVV. Le schema lui-meme empeche
--     la faute : il n'y a pas de place pour les y mettre.
-- =============================================================================

CREATE TABLE payments (
    id                      UUID            PRIMARY KEY,
    reservation_id          VARCHAR(64)     NOT NULL,
    hotel_id                VARCHAR(32)     NOT NULL,
    currency                VARCHAR(3)      NOT NULL,

    authorized_amount       BIGINT          NOT NULL CHECK (authorized_amount >= 0),
    captured_amount         BIGINT          NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
    refunded_amount         BIGINT          NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),

    status                  VARCHAR(24)     NOT NULL,

    -- Reference au moyen de paiement : un token du PSP, plus le masque autorise par
    -- PCI DSS (BIN + 4 derniers). Le numero complet reste dans le vault du PSP.
    payment_method_token    VARCHAR(128)    NOT NULL,
    card_brand              VARCHAR(20),
    card_bin                VARCHAR(8),
    card_last4              VARCHAR(4),
    card_expiry             VARCHAR(7),

    psp_reference           VARCHAR(64),
    network_transaction_id  VARCHAR(64),
    three_ds_outcome        VARCHAR(24),

    failure_code            VARCHAR(48),
    failure_reason          VARCHAR(255),

    authorization_expires_at TIMESTAMP,
    created_at              TIMESTAMP       NOT NULL,
    updated_at              TIMESTAMP       NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,

    -- Invariants monetaires garantis par la base, pas seulement par le code applicatif.
    -- Si un bug, une migration de donnees ou une correction manuelle tentait de violer
    -- ces regles, l'ecriture est rejetee. Sur de l'argent, la defense en profondeur
    -- n'est pas du luxe : le code se deploie, les donnees restent.
    CONSTRAINT chk_captured_le_authorized CHECK (captured_amount <= authorized_amount),
    CONSTRAINT chk_refunded_le_captured   CHECK (refunded_amount  <= captured_amount)
);

-- Requete du portail hotelier : liste paginee des paiements d'un hotel par date.
-- Index composite dans l'ordre (filtre, tri) pour que PostgreSQL evite le tri.
CREATE INDEX idx_payments_hotel_created ON payments (hotel_id, created_at DESC);

-- Recherche par reservation : utilisee par le support et par la reconciliation metier.
CREATE INDEX idx_payments_reservation ON payments (reservation_id);

-- Jointure avec les fichiers de settlement du PSP. Unique et partiel : deux paiements
-- ne peuvent pas partager une reference PSP, et les lignes sans reference (PENDING,
-- DECLINED) n'encombrent pas l'index.
CREATE UNIQUE INDEX idx_payments_psp_reference ON payments (psp_reference)
    WHERE psp_reference IS NOT NULL;

-- Balayage des autorisations arrivees a echeance. Partiel egalement : seules les
-- autorisations en cours interessent ce job, pas l'historique complet.
CREATE INDEX idx_payments_expiring ON payments (authorization_expires_at)
    WHERE status = 'AUTHORIZED';

-- Detection des paiements restes PENDING : le processus est probablement mort pendant
-- l'appel au PSP, et c'est la que se cachent les clients debites sans trace chez nous.
CREATE INDEX idx_payments_pending ON payments (created_at)
    WHERE status = 'PENDING';


-- =============================================================================
-- Idempotence
--
-- La cle d'idempotence est la CLE PRIMAIRE : c'est l'insertion elle-meme qui arbitre
-- la course entre deux requetes concurrentes. Un SELECT suivi d'un INSERT laisserait
-- une fenetre entre les deux, et sous charge cette fenetre est toujours exploitee --
-- avec, ici, un double debit a la cle.
-- =============================================================================

CREATE TABLE idempotency_records (
    idempotency_key     VARCHAR(128)    PRIMARY KEY,
    request_hash        VARCHAR(64)     NOT NULL,
    payment_id          UUID,
    -- 0 signifie "traitement en cours" : une requete concurrente portant la meme cle
    -- recoit un 409 au lieu de declencher une seconde autorisation.
    response_status     INTEGER         NOT NULL DEFAULT 0,
    response_body       VARCHAR(4000),
    created_at          TIMESTAMP       NOT NULL,
    expires_at          TIMESTAMP       NOT NULL
);

-- Support du job de purge (l'equivalent d'un TTL DynamoDB, qu'il faut ici expliciter).
CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);


-- =============================================================================
-- Outbox transactionnel
--
-- Les evenements sont ecrits dans la MEME transaction que le paiement : soit les deux
-- sont commites, soit aucun. On n'annonce jamais un paiement qui n'existe pas, et on
-- ne laisse jamais une reservation en attente sur un paiement pourtant autorise.
-- =============================================================================

CREATE TABLE outbox_events (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(32)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         VARCHAR(4000)   NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    attempts        INTEGER         NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP       NOT NULL,
    published_at    TIMESTAMP
);

-- File de publication. Index partiel sur les seuls PENDING : l'index reste petit meme
-- quand la table accumule des millions d'evenements publies, parce que les lignes
-- publiees en sortent automatiquement.
CREATE INDEX idx_outbox_pending ON outbox_events (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);
