/*
 * Copyright 2024 Apartium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
/**
 * TOTP (Time-based One-Time Passwords) utilities
 * When using TOTP you should use {@link net.apartium.cocoabeans.security.totp.CodeGenerator} and {@link net.apartium.cocoabeans.security.totp.CodeVerifier}
 * Warning: It is not safe to Just use {@link net.apartium.cocoabeans.security.totp.CodeGenerator} and then use String.equals() to check if the code is valid it vulnerable to timing attacks {@see https://en.wikipedia.org/wiki/Timing_attack}
 * @author Thebotgame (Kfir b.)
 */
package net.apartium.cocoabeans.security.totp;