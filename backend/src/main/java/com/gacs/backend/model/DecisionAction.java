package com.gacs.backend.model;

public enum DecisionAction {
    APPROVE(ReviewState.APPROVED), REJECT(ReviewState.REJECTED), REVISE(ReviewState.REVISION_REQUESTED);

    private final ReviewState reviewState;

    DecisionAction(ReviewState reviewState) { this.reviewState = reviewState; }
    public ReviewState reviewState() { return reviewState; }
}
