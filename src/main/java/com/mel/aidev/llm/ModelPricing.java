package com.mel.aidev.llm;

import com.mel.aidev.persistence.entity.ModelPriceEntity;
import com.mel.aidev.persistence.repository.ModelPriceRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a token count into a cost, at the tariff in force right now.
 *
 * <p>The result is stored on the LLM execution row and never recomputed: a cost recalculated from
 * today's price list would rewrite last quarter's figures every time a provider changes a tariff.
 */
@Component
public class ModelPricing {

    private final ModelPriceRepository repository;

    public ModelPricing(ModelPriceRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the cost in micro-units of the currency, or null when the model has no tariff
     */
    @Transactional(readOnly = true)
    public Long costMicros(String model, Integer promptTokens, Integer completionTokens) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return repository
                .findById(model)
                .map(price -> price.costMicros(promptTokens, completionTokens))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<ModelPriceEntity> find(String model) {
        return model == null || model.isBlank() ? Optional.empty() : repository.findById(model);
    }

    /** Currency of the price list; defaults to USD when nothing is priced yet. */
    @Transactional(readOnly = true)
    public String currency() {
        return repository.findAll().stream()
                .map(ModelPriceEntity::getCurrency)
                .findFirst()
                .orElse("USD");
    }
}
