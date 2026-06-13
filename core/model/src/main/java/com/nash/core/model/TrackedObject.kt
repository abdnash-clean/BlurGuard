package com.nash.core.model


data class TrackedObject(
    val id: FaceId?,
    val type: DetectedObjectType,
    val boundingBox: BoundingBox,
    val isTrusted: Boolean = false
)