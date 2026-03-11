package com.outlookautoemailier.security;

import com.outlookautoemailier.model.EmailAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * AES-256/GCM encrypted credential storage for OAuth2 tokens and SMTP credentials.
 *
 * <p>File layout on disk:
 * <pre>
 *   [0..15]  16 bytes  - PBKDF2 salt (random, generated once per save)
 *   [16..27] 12 bytes  - AES-GCM IV/nonce (random, generated once per save)
 *   [28..]   N bytes   - AES-256/GCM ciphertext (128-bit auth tag appended by JCE)
 * </pre>
 *
 * <p>Plaintext format inside the ciphertext is a UTF-8 encoded Java Properties file
 * where each key is prefixed with the {@link EmailAccount.AccountType} name, e.g.:
 * <pre>
 *   SOURCE.accessToken=eyJ...
 *   SOURCE.refreshToken=0.A...
 *   SENDER.accessToken=eyJ...
 * </pre>
 *
 * <p>Key derivation: PBKDF2WithHmacSHA256, 310 000 iterations, 256-bit output.
 * The password material is derived from the OS username and hostname so that the
 * encrypted file cannot be trivially decrypted on a different machine/account.
 *
 * <p>Security notes:
 * <ul>
 *   <li>A fresh salt and IV are generated on every write, preventing IV reuse.</li>
 *   <li>The 128-bit GCM authentication tag provides ciphertext integrity.</li>
 *   <li>Credential values are never written to log output.</li>
 *   <li>Byte arrays containing key material are zeroed after use.</li>
 * </ul>
 */
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    // ------------------------------------------------------------------
    //  Cryptographic constants
    // ------------------------------------------------------------------

    /** AES key size in bits. */
    private static final int KEY_SIZE_BITS = 256;

    /** AES-GCM authentication tag length in bits (NIST SP 800-38D recommended maximum). */
    private static final int GCM_TAG_BITS = 128;

    /** AES-GCM nonce/IV length in bytes (96-bit nonce is the NIST-recommended length). */
    private static final int GCM_IV_BYTES = 12;

    /** PBKDF2 salt length in bytes. */
    private static final int SALT_BYTES = 16;

    /**
     * PBKDF2 iteration count. OWASP 2023 recommendation for PBKDF2-HMAC-SHA-256
     * is 600 000; we use 310 000 as a balanced value appropriate for a desktop app
     * that must derive the key at startup without noticeable delay.
     */
    private static final int PBKDF2_ITERATIONS = 310_000;

    private static final String KDF_ALGORITHM      = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM   = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM      = "AES";

    // ------------------------------------------------------------------
    //  Storage path
    // ------------------------------------------------------------------

    private static final Path STORAGE_FILE = Paths.get(
            System.getProperty("user.home"),
            ".outlookautoemailier",
            "credentials.enc"
    );

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Encrypts and persists the supplied credentials for the given account type.
     * Any existing credentials for other account types are preserved.
     *
     * @param type        the account role whose credentials are being stored
     * @param credentials key/value pairs to store (e.g. accessToken, refreshToken)
     * @throws Exception  if key derivation, encryption, or file I/O fails
     */
    public void saveCredentials(EmailAccount.AccountType type,
                                Map<String, String> credentials) throws Exception {
        log.debug("Saving credentials for account type: {}", type);

        Map<String, Map<String, String>> allData = new HashMap<>();
        // Load existing data so we do not overwrite other account types.
        if (Files.exists(STORAGE_FILE)) {
            try {
                allData = loadAllData();
            } catch (Exception e) {
                // Treat a corrupt/missing file as empty rather than aborting the save.
                log.warn("Could not read existing credential store; overwriting. Cause: {}", e.getMessage());
            }
        }

        // Replace the entry for this type with the new data.
        allData.put(type.name(), Collections.unmodifiableMap(new HashMap<>(credentials)));
        saveAllData(allData);

        log.debug("Credentials saved successfully for account type: {}", type);
    }

    /**
     * Decrypts and returns the stored credentials for the given account type.
     *
     * @param type    the account role whose credentials are requested
     * @return        an unmodifiable map of credential key/value pairs; never {@code null}
     * @throws Exception if decryption fails or the file is absent
     */
    public Map<String, String> loadCredentials(EmailAccount.AccountType type) throws Exception {
        log.debug("Loading credentials for account type: {}", type);

        Map<String, Map<String, String>> allData = loadAllData();
        Map<String, String> result = allData.getOrDefault(type.name(), Collections.emptyMap());

        log.debug("Loaded {} credential entries for account type: {}", result.size(), type);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Removes the credentials for the given account type from the store.
     * The file is rewritten with only the remaining account types present.
     *
     * @param type    the account role to clear
     * @throws Exception if the existing data cannot be read or re-written
     */
    public void clearCredentials(EmailAccount.AccountType type) throws Exception {
        log.debug("Clearing credentials for account type: {}", type);

        if (!Files.exists(STORAGE_FILE)) {
            log.debug("Credential store does not exist; nothing to clear for type: {}", type);
            return;
        }

        Map<String, Map<String, String>> allData = loadAllData();
        allData.remove(type.name());

        if (allData.isEmpty()) {
            // Delete the file entirely when no accounts remain.
            Files.deleteIfExists(STORAGE_FILE);
            log.debug("Credential store deleted (no remaining accounts).");
        } else {
            saveAllData(allData);
            log.debug("Credentials cleared for account type: {}", type);
        }
    }

    /**
     * Returns {@code true} if the credential store contains an entry for the
     * given account type. This performs a full decrypt to guarantee data integrity;
     * it does not merely check for file existence.
     *
     * @param type the account role to test
     * @return {@code true} if credentials exist and the file can be decrypted
     */
    public boolean hasCredentials(EmailAccount.AccountType type) {
        if (!Files.exists(STORAGE_FILE)) {
            return false;
        }
        try {
            Map<String, Map<String, String>> allData = loadAllData();
            boolean has = allData.containsKey(type.name())
                    && !allData.get(type.name()).isEmpty();
            log.debug("hasCredentials({}) = {}", type, has);
            return has;
        } catch (Exception e) {
            log.warn("hasCredentials check failed for type {}: {}", type, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Internal helpers — cryptography
    // ------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} using AES-256/GCM.
     *
     * @param plaintext the bytes to encrypt
     * @param key       the AES-256 secret key
     * @param iv        the 12-byte GCM nonce
     * @return          ciphertext with the 16-byte GCM authentication tag appended
     * @throws Exception on JCE failure
     */
    private byte[] encrypt(byte[] plaintext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
        return cipher.doFinal(plaintext);
    }

    /**
     * Decrypts AES-256/GCM ciphertext (with appended authentication tag).
     * Throws {@link javax.crypto.AEADBadTagException} if the tag does not verify,
     * which indicates tampering or an incorrect key.
     *
     * @param ciphertext the encrypted bytes including the 16-byte GCM tag
     * @param key        the AES-256 secret key
     * @param iv         the 12-byte GCM nonce
     * @return           the original plaintext bytes
     * @throws Exception on JCE failure or authentication tag mismatch
     */
    private byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        return cipher.doFinal(ciphertext);
    }

    /**
     * Derives a 256-bit AES key from the machine-specific password material
     * using PBKDF2WithHmacSHA256 and the supplied salt.
     *
     * <p>The password is {@code "<os-username>@<hostname>"}, giving basic
     * machine-binding without requiring a separate master password.
     *
     * @param salt 16 random bytes
     * @return     a {@link SecretKeySpec} ready for AES operations
     * @throws Exception on JCE or network failure resolving hostname
     */
    private SecretKey deriveKey(byte[] salt) throws Exception {
        String username = System.getProperty("user.name");
        String hostname = InetAddress.getLocalHost().getHostName();
        String passwordMaterial = username + "@" + hostname;

        log.debug("Deriving key for identity: {}@{}", username, hostname);

        char[] password = passwordMaterial.toCharArray();
        byte[] passwordBytes = passwordMaterial.getBytes(StandardCharsets.UTF_8);

        try {
            KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
            // Zero out the raw key bytes immediately after wrapping.
            Arrays.fill(keyBytes, (byte) 0);
            return key;
        } finally {
            // Zero the password array to limit its lifetime in heap memory.
            Arrays.fill(password, '\0');
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    // ------------------------------------------------------------------
    //  Internal helpers — serialisation / file I/O
    // ------------------------------------------------------------------

    /**
     * Serialises {@code allData}, encrypts it with a freshly generated salt and
     * IV, and atomically writes the result to {@link #STORAGE_FILE}.
     *
     * <p>The file format is: {@code [16-byte salt][12-byte IV][ciphertext]}.
     *
     * @param allData the complete in-memory credential map
     * @throws Exception on serialisation, crypto, or I/O failure
     */
    private void saveAllData(Map<String, Map<String, String>> allData) throws Exception {
        // Flatten the nested map into a Properties object.
        Properties props = new Properties();
        for (Map.Entry<String, Map<String, String>> typeEntry : allData.entrySet()) {
            String prefix = typeEntry.getKey();
            for (Map.Entry<String, String> credEntry : typeEntry.getValue().entrySet()) {
                props.setProperty(prefix + "." + credEntry.getKey(), credEntry.getValue());
            }
        }

        // Serialise Properties to a UTF-8 byte array.
        byte[] plaintext;
        try (ByteArrayOutputStream boas = new ByteArrayOutputStream()) {
            props.store(boas, null);
            plaintext = boas.toByteArray();
        }

        // Generate fresh random salt and IV for this write.
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv   = new byte[GCM_IV_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        SecretKey key = deriveKey(salt);
        byte[] ciphertext = encrypt(plaintext, key, iv);

        // Zero plaintext in memory after encryption.
        Arrays.fill(plaintext, (byte) 0);

        // Build file payload: [salt][iv][ciphertext]
        byte[] fileBytes = new byte[SALT_BYTES + GCM_IV_BYTES + ciphertext.length];
        System.arraycopy(salt,       0, fileBytes, 0,                        SALT_BYTES);
        System.arraycopy(iv,         0, fileBytes, SALT_BYTES,               GCM_IV_BYTES);
        System.arraycopy(ciphertext, 0, fileBytes, SALT_BYTES + GCM_IV_BYTES, ciphertext.length);

        // Ensure parent directory exists.
        Path parent = STORAGE_FILE.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
            log.debug("Created credential store directory: {}", parent);
        }

        // Write atomically: write to a temp file then move it over.
        Path tmp = STORAGE_FILE.resolveSibling("credentials.enc.tmp");
        Files.write(tmp, fileBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, STORAGE_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        log.debug("Credential store written ({} bytes).", fileBytes.length);

        // Zero sensitive arrays.
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(iv,   (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
        Arrays.fill(fileBytes, (byte) 0);
    }

    /**
     * Reads and decrypts the credential store from {@link #STORAGE_FILE},
     * returning the full nested credential map.
     *
     * @return a mutable map of {@code accountTypeName -> (credentialKey -> value)}
     * @throws Exception if the file does not exist, has been corrupted, or the
     *                   GCM authentication tag does not verify
     */
    private Map<String, Map<String, String>> loadAllData() throws Exception {
        if (!Files.exists(STORAGE_FILE)) {
            throw new IOException("Credential store not found: " + STORAGE_FILE);
        }

        byte[] fileBytes = Files.readAllBytes(STORAGE_FILE);

        if (fileBytes.length < SALT_BYTES + GCM_IV_BYTES + GCM_TAG_BITS / 8) {
            throw new IOException("Credential store file is too short to be valid.");
        }

        // Extract salt and IV from the file header.
        byte[] salt = Arrays.copyOfRange(fileBytes, 0, SALT_BYTES);
        byte[] iv   = Arrays.copyOfRange(fileBytes, SALT_BYTES, SALT_BYTES + GCM_IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(fileBytes, SALT_BYTES + GCM_IV_BYTES, fileBytes.length);

        SecretKey key = deriveKey(salt);
        byte[] plaintext;
        try {
            plaintext = decrypt(ciphertext, key, iv);
        } finally {
            // Zero sensitive intermediate arrays.
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv,   (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(fileBytes, (byte) 0);
        }

        // Deserialise Properties from the plaintext bytes.
        Properties props = new Properties();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(plaintext)) {
            props.load(bais);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        // Reconstruct the nested map: prefix -> (key -> value).
        Map<String, Map<String, String>> allData = new HashMap<>();
        for (String propKey : props.stringPropertyNames()) {
            int dotIdx = propKey.indexOf('.');
            if (dotIdx < 1 || dotIdx == propKey.length() - 1) {
                log.warn("Ignoring malformed credential property key: {}", propKey);
                continue;
            }
            String typePrefix   = propKey.substring(0, dotIdx);
            String credentialKey = propKey.substring(dotIdx + 1);
            allData.computeIfAbsent(typePrefix, k -> new HashMap<>())
                   .put(credentialKey, props.getProperty(propKey));
        }

        log.debug("Credential store loaded; {} account type(s) present.", allData.size());
        return allData;
    }
}
