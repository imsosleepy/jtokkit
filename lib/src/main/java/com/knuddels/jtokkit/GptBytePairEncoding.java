package com.knuddels.jtokkit;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.GptBytePairEncodingParams;
import com.knuddels.jtokkit.api.IntArrayList;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Implementation of the byte pair encoding algorithm as used by the OpenAI tiktoken tokenizer.
 */
class GptBytePairEncoding implements Encoding {

    final TokenEncoder encoder;
    private final String name;
    private final Pattern pattern;
    private final SpecialEncoder specialEncoder;

    /**
     * Creates a new instance of {@link GptBytePairEncoding}.
     *
     * @param params the parameters to use for the encoding
     */
    GptBytePairEncoding(GptBytePairEncodingParams params) {
        this.name = params.getName();
        this.pattern = params.getPattern();
        this.encoder = new TokenEncoder(params.getEncoder());
        this.specialEncoder = new SpecialEncoder(params.getSpecialTokensEncoder());
    }

    @Override
    public IntArrayList encode(String text) {
        return encode(text, Integer.MAX_VALUE).getTokens();
    }

    @Override
    public EncodingResult encode(String text, int maxTokenCount) {
        return encodeInternal(text, maxTokenCount, true).toEncodingResult();
    }

    private InternalResult encodeInternal(String text, int maxTokenCount, boolean keepEncodings) {
        if (text == null) {
            return new InternalResult(new IntArrayList(0), false);
        }

        specialEncoder.checkForSpecialTokens(text);

        return encodeOrdinaryInternal(text, maxTokenCount, keepEncodings);
    }

    @Override
    public IntArrayList encodeOrdinary(String text) {
        return encodeOrdinary(text, Integer.MAX_VALUE).getTokens();
    }

    @Override
    public EncodingResult encodeOrdinary(String text, int maxTokenCount) {
        return encodeOrdinaryInternal(text, maxTokenCount, true).toEncodingResult();
    }

    private InternalResult encodeOrdinaryInternal(String text, int maxTokenCount, boolean keepEncodings) {
        if (text == null) {
            return new InternalResult(new IntArrayList(0), false);
        }

        IntArrayList out = new IntArrayList();
        int tokenCount = encodeOrdinaryInternal(text, maxTokenCount, keepEncodings, out);

        if (keepEncodings && maxTokenCount != Integer.MAX_VALUE) {
            // Make sure we didn't break the multibyte character
            for (int tokensToRemove = 0; tokensToRemove <= out.size(); tokensToRemove++) {
                int size = out.size() - tokensToRemove;
                IntArrayList tokens = new IntArrayList(size);
                for (int i = 0; i < size; i++) {
                    tokens.add(out.get(i));
                }
                String decoded = decode(tokens);
                if (text.startsWith(decoded)) {
                    boolean truncated = text.length() > decoded.length();
                    int lastTokenIndex = findLastTokenIndex(text, decoded);
                    // If decoded text is equal to the head of the original text, we can safely return the tokens
                    return new InternalResult(tokens, truncated, truncated ? lastTokenIndex : text.length() - 1);
                }
            }
        }

        return new InternalResult(out, tokenCount, false);
    }

    int encodeOrdinaryInternal(String text, int maxTokenCount, boolean keepEncodings, IntArrayList out) {
        int tokenCount = 0;
        IntArrayList ranks = new IntArrayList(); // reused to avoid allocations
        for (Matcher matcher = pattern.matcher(text); tokenCount < maxTokenCount && matcher.find(); ) {
            byte[] bytes = matcher.group().getBytes(UTF_8);
            tokenCount += encoder.addTokensAndGetCount(maxTokenCount, keepEncodings, bytes, out, ranks);
        }
        return tokenCount;
    }

    @Override
    public int countTokens(String text) {
        return encodeInternal(text, Integer.MAX_VALUE, false).toTokenCount();
    }

    @Override
    public int countTokensOrdinary(final String text) {
        return encodeOrdinaryInternal(text, Integer.MAX_VALUE, false).toTokenCount();
    }

    @Override
    public String decode(IntArrayList tokens) {
        return new String(decodeBytes(tokens), UTF_8);
    }

    @Override
    public byte[] decodeBytes(IntArrayList tokens) {
        ByteArrayList out = new ByteArrayList(10 * tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            byte[] decodedToken = decodeToken(tokens.get(i));
            for (byte b : decodedToken) {
                out.add(b);
            }
        }
        return out.toArray();
    }

    @Override
    public String getName() {
        return name;
    }

    private byte[] decodeToken(int token) {
        byte[] decodedToken = encoder.decodeToken(token, specialEncoder);
        return requireNonNull(decodedToken, "Unknown token for decoding: " + token);
    }

    private int findLastTokenIndex(String text, String decode) {
        for (int i = 0; i < decode.length(); i++) {
            if (text.charAt(i) != decode.charAt(i)) {
                return i - 1;
            }
        }
        return decode.length() - 1;
    }

    private static final class InternalResult {
        private final IntArrayList tokens;
        private final boolean truncated;
        private final int tokenCount;
        private final int lastTokenIndex;

        private InternalResult(IntArrayList tokens, boolean truncated) {
            this(tokens, -1, truncated, -1);
        }

        private InternalResult(IntArrayList tokens, int tokenCount, boolean truncated) {
            this(tokens, tokenCount, truncated, -1);
        }

        private InternalResult(IntArrayList tokens, boolean truncated, int lastTokenIndex) {
            this(tokens, -1, truncated, lastTokenIndex);
        }

        private InternalResult(IntArrayList tokens, int tokenCount, boolean truncated, int lastTokenIndex) {
            this.tokens = tokens;
            this.truncated = truncated;
            this.tokenCount = tokenCount < 0 ? tokens.size() : tokenCount;
            this.lastTokenIndex = lastTokenIndex;
        }

        private EncodingResult toEncodingResult() {
            if (tokens.size() != tokenCount) {
                throw new IllegalStateException(
                        "Token count does not match token list size (tokenCount=" + tokenCount + ", tokens size=" + tokens.size() + ")"
                );
            }

            return new EncodingResult(tokens, truncated, lastTokenIndex);
        }

        private int toTokenCount() {
            return tokenCount;
        }
    }
}
