/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

import java.util.List;

/**
 * The errors of the protocol the assistant surface speaks, worded by this
 * service. The texts are in {@code messages/notice.properties}.
 *
 * <p>A request that is no call of a tool is answered as an error of the
 * protocol rather than refused in the envelope. What arrived -- a method, a
 * tool name -- is the caller's input and is never repeated; an error names
 * what this server has instead (DEC-0043 R19, R20).
 */
@MessageBundle("notice")
public interface Notices {

    @Message String bodyNotJson();

    @Message String oneRequest();

    @Message String notJsonRpc();

    @Message String methods();

    @Message String paramsObject();

    @Message String tools(List<VerbName> tools);

    @Message String argumentsObject();
}
