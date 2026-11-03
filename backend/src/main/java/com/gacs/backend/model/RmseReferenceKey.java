package com.gacs.backend.model;

import lombok.*;

import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class RmseReferenceKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private String modelKind; // matches @Id field name 'modelKind' in RmseReference
    private String target;    // matches @Id field name 'target' in RmseReference
    private Integer hourUtc;  // matches @Id field name 'hourUtc' in RmseReference
}
