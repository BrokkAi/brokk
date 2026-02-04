package declpkg

var MyGlobalVar int = 42
const MyGlobalConst = "hello_const"

func MyTopLevelFunction(param int) string {
	return "hello"
}

type MyStruct struct {
	FieldA int
}

type MyInterface interface {
	DoSomething()
}

// Add this method for MyStruct
func (s MyStruct) GetFieldA() int {
	return s.FieldA
}

type Uint32Map map[uint32]struct{}
type StringAlias = string  // True alias - should be FIELD_LIKE
type MyInt int             // Named type - should be CLASS_LIKE (can have methods)

func (m MyInt) String() string { return "" }

type (
    GroupedNamedType map[string]int
    GroupedAlias     = []byte
)

func anotherFunc() {}
