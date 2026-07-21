package com.authme.hytale.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Data sent from the login/register UI pages back to the server.
 */
public final class AuthEventData {

    public static final BuilderCodec<AuthEventData> CODEC = BuilderCodec
        .builder(AuthEventData.class, AuthEventData::new)
        .append(
            new KeyedCodec<>("Action", Codec.STRING),
            (data, value) -> data.action = value,
            data -> data.action)
        .add()
        .append(
            new KeyedCodec<>("@Password", Codec.STRING),
            (data, value) -> data.password = value,
            data -> data.password)
        .add()
        .append(
            new KeyedCodec<>("@Confirm", Codec.STRING),
            (data, value) -> data.confirm = value,
            data -> data.confirm)
        .add()
        .build();

    private String action;
    private String password;
    private String confirm;

    public String getAction() {
        return action;
    }

    public String getPassword() {
        return password;
    }

    public String getConfirm() {
        return confirm;
    }
}
