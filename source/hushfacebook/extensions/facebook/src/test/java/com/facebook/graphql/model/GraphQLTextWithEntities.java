/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.model;

import com.facebook.graphql.modelutil.BaseModelWithTree;

/**
 * A test stand-in for Facebook's kept text model, tagged as a TextWithEntities tree is. The
 * comment filter reads its {@code text} through the kept {@code getCachedString(int)}.
 */
public class GraphQLTextWithEntities extends BaseModelWithTree {
    /** The tag Facebook's accessors pass for a TextWithEntities: its name's MD5, first four bytes. */
    public static final int TYPE_TAG = 0xdb1d8904;

    public GraphQLTextWithEntities(String text) {
        super(TYPE_TAG);
        with("text", text);
    }
}
