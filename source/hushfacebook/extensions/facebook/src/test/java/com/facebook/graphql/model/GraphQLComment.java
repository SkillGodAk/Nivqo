/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.model;

import com.facebook.graphql.modelutil.BaseModelWithTree;

/**
 * A test stand-in for Facebook's kept comment model. The comment filter reads its {@code body}
 * through the kept {@code getCachedModel(int, Class, int)}, keyed by the field name's hash.
 */
public class GraphQLComment extends BaseModelWithTree {
    /** A comment whose body says [text], or one with no body when it's null. */
    public GraphQLComment(String text) {
        super(0);
        if (text != null) withModel("body", new GraphQLTextWithEntities(text));
    }
}
