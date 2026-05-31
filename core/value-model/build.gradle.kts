plugins {
    id("homesynapse.library-conventions")
}

description = "Value model: AttributeValue hierarchy and AttributeType (leaf — requires only java.base)"

// No dependencies — com.homesynapse.value is a leaf module that depends on
// nothing but java.base. See the AttributeValue Module Relocation Design Note
// (2026-05-31). The value types' unit tests remain in core:device-model for
// the spike; value-model has no test source set.
