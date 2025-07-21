# This is a top level comment, should be ignored.

class Parent:
    PARENT_CLASS_VAR = "parent_cls_val"
    # This comment is inside Parent, before NestedClass.
    # It should not appear in Parent's skeleton.

    class NestedClass:
        NESTED_CLASS_VAR = 123
        # This comment is inside NestedClass, before a method.
        # It should not appear in NestedClass's skeleton.

        def __init__(self):
            # This comment is inside NestedClass.__init__
            self.nested_instance_var = "nested_inst_val"
            pass

        def nested_method(self):
            # This comment is inside a nested method
            return "nested_method_result"

    # This comment is inside Parent, after NestedClass.
    # It should not appear in Parent's skeleton.

    def parent_method(self):
        # This comment is inside a parent method
        self.parent_instance_var = "parent_inst_val"
        return "parent_method_result"

# Another top-level comment.
