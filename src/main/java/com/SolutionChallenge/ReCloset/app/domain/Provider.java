package com.SolutionChallenge.ReCloset.app.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Provider {
    NORMAL("Normal"),
    GOOGLE("Google");

    private final String key;
}


