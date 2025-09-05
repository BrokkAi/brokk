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

func anotherFunc() {}
