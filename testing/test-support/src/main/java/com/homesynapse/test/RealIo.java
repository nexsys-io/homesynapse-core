/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class or method as intentionally performing real I/O
 * (network, filesystem, or both).
 *
 * <p>Tests annotated with {@code @RealIo} are exempt from the ArchUnit rule
 * that prohibits direct {@code java.io.File}, {@code java.nio.file.Files},
 * {@code java.net.Socket}, and {@code java.net.HttpURLConnection} usage
 * in test code. They are also exempt from the {@link NoRealIoExtension}
 * proxy selector guard.
 *
 * <p>Use sparingly. The vast majority of tests should use in-memory replacements.
 *
 * @see NoRealIoExtension
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RealIo {

    /**
     * Optional reason explaining why real I/O is needed.
     *
     * @return the justification
     */
    String value() default "";
}
