package com.example.repository;

import com.example.model.User;
import java.util.List;
import java.util.Optional;

public class UserRepository {
    
    public void save(User user) {
        // Implementation would go here
    }
    
    public Optional<User> findById(Long id) {
        // Implementation would go here
        return Optional.empty();
    }
    
    public List<User> findAll() {
        // Implementation would go here
        return List.of();
    }
    
    public void delete(User user) {
        // Implementation would go here
    }
}
