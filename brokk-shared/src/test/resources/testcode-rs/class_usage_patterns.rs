/// Test file to verify Rust class usage patterns

pub struct BaseStruct {
    value: i32,
}

impl BaseStruct {
    pub fn new(value: i32) -> Self {
        BaseStruct { value }
    }

    pub fn static_method() -> String {
        "static".to_string()
    }
}

pub trait BaseTrait {
    fn process(&self);
}

// Trait implementation
impl BaseTrait for BaseStruct {
    fn process(&self) {
        println!("Processing");
    }
}

pub struct ExtendedStruct {
    // Field with type annotation
    base: BaseStruct,
}

impl ExtendedStruct {
    pub fn new(param: BaseStruct) -> Self {
        // Struct initialization
        let local = BaseStruct { value: 42 };

        // Associated function call
        let _result = BaseStruct::static_method();

        ExtendedStruct { base: param }
    }

    // Method with type annotations
    pub fn process_base(&self, input: BaseStruct) -> BaseStruct {
        input
    }

    // Generic usage
    pub fn get_vec(&self) -> Vec<BaseStruct> {
        vec![BaseStruct::new(1)]
    }
}

// Variable with type annotation
pub fn use_base() {
    let my_base: BaseStruct = BaseStruct::new(10);
}
