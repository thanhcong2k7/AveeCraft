package com.larvalabs.svgandroid;
/* loaded from: classes2.dex */
public class ParserHelper {
    private static final double[] pow10 = new double[128];
    private char current;
    private final int n;
    public int pos;
    private final char[] s;

    public ParserHelper(String str, int i) {
        try {
            char[] charArray = str.toCharArray();
            this.s = charArray;
            this.pos = i;
            this.n = charArray.length;
            this.current = charArray[i];
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private char read() {
        int i = this.pos;
        if (i < this.n) {
            this.pos = i + 1;
        }
        int i2 = this.pos;
        if (i2 == this.n) {
            return (char) 0;
        }
        return this.s[i2];
    }

    public void skipWhitespace() {
        while (true) {
            int i = this.pos;
            if (i >= this.n || !Character.isWhitespace(this.s[i])) {
                return;
            }
            advance();
        }
    }

    public void skipNumberSeparator() {
        while (true) {
            int i = this.pos;
            if (i >= this.n) {
                return;
            }
            char c = this.s[i];
            if (c != '\t' && c != '\n' && c != ' ' && c != ',') {
                return;
            }
            advance();
        }
    }

    public void advance() {
        this.current = read();
    }

    public float parseFloat() {
        int mant = 0;
        int mantDig = 0;
        boolean mantPos = true;
        boolean mantRead = false;

        int exp = 0;
        int expDig = 0;
        int expAdj = 0;
        boolean expPos = true;

        switch (this.current) {
            case '-':
                mantPos = false;
                // fallthrough
            case '+':
                this.current = read();
                break;
        }

        m1: switch (this.current) {
            default:
                return 0.0f;

            case '.':
                break;

            case '0':
                mantRead = true;
                l: while (true) {
                    this.current = read();
                    switch (this.current) {
                        case '1': case '2': case '3': case '4':
                        case '5': case '6': case '7': case '8': case '9':
                            break l;
                        case '.': case 'e': case 'E':
                            break m1;
                        default:
                            return 0.0f;
                        case '0':
                    }
                }
                // fallthrough
            case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                mantRead = true;
                l2: while (true) {
                    if (mantDig < 9) {
                        mantDig++;
                        mant = mant * 10 + (this.current - '0');
                    } else {
                        expAdj++;
                    }
                    this.current = read();
                    switch (this.current) {
                        default:
                            break l2;
                        case '0': case '1': case '2': case '3': case '4':
                        case '5': case '6': case '7': case '8': case '9':
                    }
                }
        }

        if (this.current == '.') {
            this.current = read();
            m2: switch (this.current) {
                default:
                case 'e': case 'E':
                    if (!mantRead) {
                        reportUnexpectedCharacterError(this.current);
                        return 0.0f;
                    }
                    break;

                case '0':
                    if (mantDig == 0) {
                        l3: while (true) {
                            this.current = read();
                            expAdj--;
                            switch (this.current) {
                                case '1': case '2': case '3': case '4':
                                case '5': case '6': case '7': case '8': case '9':
                                    break l3;
                                default:
                                    if (!mantRead) {
                                        return 0.0f;
                                    }
                                    break m2;
                                case '0':
                            }
                        }
                    }
                    // fallthrough
                case '1': case '2': case '3': case '4':
                case '5': case '6': case '7': case '8': case '9':
                    l4: while (true) {
                        if (mantDig < 9) {
                            mantDig++;
                            mant = mant * 10 + (this.current - '0');
                            expAdj--;
                        }
                        this.current = read();
                        switch (this.current) {
                            default:
                                break l4;
                            case '0': case '1': case '2': case '3': case '4':
                            case '5': case '6': case '7': case '8': case '9':
                        }
                    }
            }
        }

        switch (this.current) {
            case 'e': case 'E':
                this.current = read();
                switch (this.current) {
                    default:
                        reportUnexpectedCharacterError(this.current);
                        return 0.0f;
                    case '-':
                        expPos = false;
                        // fallthrough
                    case '+':
                        this.current = read();
                        switch (this.current) {
                            default:
                                reportUnexpectedCharacterError(this.current);
                                return 0.0f;
                            case '0': case '1': case '2': case '3': case '4':
                            case '5': case '6': case '7': case '8': case '9':
                        }
                        // fallthrough
                    case '0': case '1': case '2': case '3': case '4':
                    case '5': case '6': case '7': case '8': case '9':
                }

                en: switch (this.current) {
                    case '0':
                        l5: while (true) {
                            this.current = read();
                            switch (this.current) {
                                default:
                                    break en;
                                case '1': case '2': case '3': case '4':
                                case '5': case '6': case '7': case '8': case '9':
                                    break l5;
                                case '0':
                            }
                        }
                        // fallthrough
                    case '1': case '2': case '3': case '4':
                    case '5': case '6': case '7': case '8': case '9':
                        l6: while (true) {
                            if (expDig < 3) {
                                expDig++;
                                exp = exp * 10 + (this.current - '0');
                            }
                            this.current = read();
                            switch (this.current) {
                                default:
                                    break l6;
                                case '0': case '1': case '2': case '3': case '4':
                                case '5': case '6': case '7': case '8': case '9':
                            }
                        }
                }
            default:
        }

        if (!expPos) {
            exp = -exp;
        }
        exp += expAdj;
        if (!mantPos) {
            mant = -mant;
        }
        return buildFloat(mant, exp);
    }

    private void reportUnexpectedCharacterError(char c) {
        throw new RuntimeException("Unexpected char '" + c + "'.");
    }

    public static float buildFloat(int i, int i2) {
        double d;
        if (i2 < -125 || i == 0) {
            return 0.0f;
        }
        if (i2 >= 128) {
            return i > 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        } else if (i2 == 0) {
            return i;
        } else {
            if (i >= 67108864) {
                i++;
            }
            double d2 = i;
            double[] dArr = pow10;
            if (i2 > 0) {
                double d3 = dArr[i2];
                Double.isNaN(d2);
                d = d2 * d3;
            } else {
                double d4 = dArr[-i2];
                Double.isNaN(d2);
                d = d2 / d4;
            }
            return (float) d;
        }
    }

    static {
        int i = 0;
        while (true) {
            double[] dArr = pow10;
            if (i >= dArr.length) {
                break;
            }
            dArr[i] = Math.pow(10.0d, i);
            i++;
        }
    }

    public float nextFloat() {
        skipWhitespace();
        float parseFloat = parseFloat();
        skipNumberSeparator();
        return parseFloat;
    }

    public int nextFlag() {
        skipWhitespace();
        int i = this.current - '0';
        this.current = read();
        skipNumberSeparator();
        return i;
    }
}
