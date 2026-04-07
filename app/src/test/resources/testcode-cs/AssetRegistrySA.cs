using Arc4u.ServiceModel; // Assuming this is a type, not strictly needed for parsing structure
using System.Threading.Tasks; // For Task<>
using System; // For Guid

namespace ConsumerCentricityPermission.Core.ISA
{
    public interface IAssetRegistrySA
    {
        public Task<Message> ValidateExistenceAsync(Guid assetId);
        public Task<bool> CanConnectAsync();
        public Task<string> GetDeliveryPointDescriptionAsync(Guid deliveryPointId);
    }
}
