package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;

public interface ModelRouter {
    String policy();

    RoutingDecision route(GenerateRequest request);
}
