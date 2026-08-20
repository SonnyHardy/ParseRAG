-- Retrait de la cle semee par V1 (issue #56).
--
-- V1 inserait le hash SHA-256 de « 123 » : toute base neuve contenait donc une cle API active
-- dont le secret est trivial et dont le hash est lisible dans le depot. Un appel direct a
-- l'origine contournait ainsi la place de marche - ni quota, ni facturation.
--
-- V1 n'est pas editee : son checksum est deja applique, et le modifier casserait la validation
-- Flyway sur toute base existante. On supprime donc la ligne ici.
--
-- La cle d'administration ne vient plus d'une migration : elle est creee au demarrage a partir
-- de ADMIN_KEY_HASH (AdminKeyBootstrap). Un secret, meme sous forme de hash devinable, n'a rien
-- a faire dans un fichier versionne.
DELETE FROM api_keys
WHERE key_hash = 'a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3';
