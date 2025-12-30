package main

// BaseStruct is a test struct
type BaseStruct struct {
	Value int
}

// StaticMethod is a function associated with BaseStruct
func StaticMethod() string {
	return "static"
}

// InstanceMethod is a method on BaseStruct
func (b *BaseStruct) InstanceMethod() string {
	return "instance"
}

// ExtendedStruct embeds BaseStruct
type ExtendedStruct struct {
	BaseStruct
	field *BaseStruct
}

// NewExtendedStruct creates a new ExtendedStruct
func NewExtendedStruct(param *BaseStruct) *ExtendedStruct {
	// Struct initialization
	local := BaseStruct{Value: 42}

	// Pointer type
	ptr := &BaseStruct{Value: 10}

	return &ExtendedStruct{
		BaseStruct: local,
		field:      ptr,
	}
}

// ProcessBase takes and returns BaseStruct
func (e *ExtendedStruct) ProcessBase(input *BaseStruct) *BaseStruct {
	return input
}

// GetSlice returns a slice of BaseStruct
func (e *ExtendedStruct) GetSlice() []*BaseStruct {
	return []*BaseStruct{{Value: 1}}
}

func usageExample() {
	// Variable with type
	var myBase BaseStruct
	var ptr *BaseStruct = &BaseStruct{Value: 5}
}
