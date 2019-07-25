/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.component.installer.ce;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.util.Map;
import java.util.Properties;
import org.graalvm.component.installer.BundleConstants;
import org.graalvm.component.installer.CommandInput;
import org.graalvm.component.installer.CommonConstants;
import org.graalvm.component.installer.Feedback;
import org.graalvm.component.installer.IncompatibleException;
import org.graalvm.component.installer.model.ComponentRegistry;
import org.graalvm.component.installer.model.ComponentStorage;
import org.graalvm.component.installer.remote.FileDownloader;
import org.graalvm.component.installer.remote.RemotePropertiesStorage;
import org.graalvm.component.installer.SoftwareChannel;
import org.graalvm.component.installer.SoftwareChannelSource;
import org.graalvm.component.installer.model.ComponentInfo;

public class WebCatalog implements SoftwareChannel {
    private final String urlString;
    private final SoftwareChannelSource source;

    private URL catalogURL;
    private CommandInput input;
    private Feedback feedback;
    private ComponentRegistry local;
    private ComponentStorage storage;

    public WebCatalog(String u, SoftwareChannelSource source) {
        this.urlString = u;
        this.source = source;
    }

    protected static boolean acceptURLScheme(String scheme) {
        switch (scheme) {
            case "http":    // NOI18N
            case "https":   // NOI18N
            case "ftp":     // NOI18N
            case "ftps":    // NOI18N
            case "file":
                return true;
        }
        return false;
    }

    public void init(CommandInput in, Feedback out) {
        assert this.input == null;

        this.input = in;
        this.feedback = out.withBundle(WebCatalog.class);
        this.local = in.getLocalRegistry();
    }

    @Override
    public ComponentStorage getStorage() {
        if (this.storage != null) {
            return this.storage;
        }

        Map<String, String> graalCaps = local.getGraalCapabilities();

        StringBuilder sb = new StringBuilder();
        sb.append(graalCaps.get(CommonConstants.CAP_OS_NAME).toLowerCase());
        sb.append("_");
        sb.append(graalCaps.get(CommonConstants.CAP_OS_ARCH).toLowerCase());

        try {
            catalogURL = new URL(urlString);
        } catch (MalformedURLException ex) {
            throw feedback.failure("REMOTE_InvalidURL", ex, catalogURL, ex.getLocalizedMessage());
        }

        Properties props = new Properties();
        // create the storage. If the init fails, but process will not terminate, the storage will
        // serve no components on the next call.
        storage = new RemotePropertiesStorage(feedback, local, props, sb.toString(), null, catalogURL);

        Properties loadProps = new Properties();
        FileDownloader dn;
        try {
            catalogURL = new URL(urlString);
            String l = source.getLabel();
            dn = new FileDownloader(feedback.l10n(l == null || l.isEmpty() ? "REMOTE_CatalogLabel2" : "REMOTE_CatalogLabel", l), catalogURL, feedback);
            dn.download();
        } catch (NoRouteToHostException | ConnectException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalogProxy", ex, catalogURL, ex.getLocalizedMessage());
        } catch (FileNotFoundException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalogNotFound", ex, catalogURL);
        } catch (IOException ex) {
            throw feedback.failure("REMOTE_ErrorDownloadCatalog", ex, catalogURL, ex.getLocalizedMessage());
        }

        StringBuilder oldGraalPref = new StringBuilder(BundleConstants.GRAAL_COMPONENT_ID);
        oldGraalPref.append('.');

        String graalVersionString = graalCaps.get(CommonConstants.CAP_GRAALVM_VERSION).toLowerCase();
        String normalizedVersion = input.getLocalRegistry().getGraalVersion().toString();

        StringBuilder graalPref = new StringBuilder(oldGraalPref);

        oldGraalPref.append(graalVersionString);

        oldGraalPref.append('_').append(sb);
        graalPref.append(sb).append('/');
        graalPref.append(normalizedVersion);

        try (FileInputStream fis = new FileInputStream(dn.getLocalFile())) {
            loadProps.load(fis);
        } catch (IllegalArgumentException | IOException ex) {
            throw feedback.failure("REMOTE_CorruptedCatalogFile", ex, catalogURL);
        }

        if (loadProps.getProperty(oldGraalPref.toString()) == null &&
                        loadProps.getProperty(graalPref.toString()) == null) {
            boolean graalPrefixFound = false;
            boolean componentFound = false;
            for (String s : loadProps.stringPropertyNames()) {
                if (s.startsWith(BundleConstants.GRAAL_COMPONENT_ID)) {
                    graalPrefixFound = true;
                }
                if (s.startsWith("Component.")) {
                    componentFound = true;
                }
            }
            if (!(graalPrefixFound && componentFound)) {
                throw feedback.failure("REMOTE_CorruptedCatalogFile", null, catalogURL);
            } else {
                throw new IncompatibleException(
                                feedback.l10n("REMOTE_UnsupportedGraalVersion",
                                                graalCaps.get(CommonConstants.CAP_GRAALVM_VERSION),
                                                graalCaps.get(CommonConstants.CAP_OS_NAME),
                                                graalCaps.get(CommonConstants.CAP_OS_ARCH)),
                                null);
            }
        }
        props.putAll(loadProps);
        return storage;
    }

    @Override
    public FileDownloader configureDownloader(ComponentInfo cInfo, FileDownloader dn) {
        return dn;
    }

    public static class WebCatalogFactory implements SoftwareChannel.Factory {
        private CommandInput input;

        @Override
        public SoftwareChannel createChannel(SoftwareChannelSource src, CommandInput in, Feedback fb) {
            String urlSpec = src.getLocationURL();
            int schColon = urlSpec.indexOf(':'); // NOI18N
            if (schColon == -1) {
                return null;
            }
            String scheme = urlSpec.toLowerCase().substring(0, schColon);
            if (acceptURLScheme(scheme)) {
                WebCatalog c = new WebCatalog(urlSpec, src);
                c.init(in, fb);
                return c;
            }
            return null;
        }

        @Override
        public void init(CommandInput in, Feedback out) {
            assert this.input == null;
            this.input = in;
        }
    }
}
