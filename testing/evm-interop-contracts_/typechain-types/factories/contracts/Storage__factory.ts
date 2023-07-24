/* Autogenerated file. Do not edit manually. */
/* tslint:disable */
/* eslint-disable */
import {
  Contract,
  ContractFactory,
  ContractTransactionResponse,
  Interface,
} from "ethers";
import type { Signer, ContractDeployTransaction, ContractRunner } from "ethers";
import type { NonPayableOverrides } from "../../common";
import type { Storage, StorageInterface } from "../../contracts/Storage";

const _abi = [
  {
    inputs: [],
    name: "retrieve",
    outputs: [
      {
        internalType: "uint256",
        name: "",
        type: "uint256",
      },
    ],
    stateMutability: "view",
    type: "function",
  },
  {
    inputs: [
      {
        internalType: "address",
        name: "name",
        type: "address",
      },
    ],
    name: "retrieveStruct",
    outputs: [
      {
        components: [
          {
            internalType: "int256",
            name: "x1",
            type: "int256",
          },
          {
            components: [
              {
                internalType: "int256",
                name: "x2",
                type: "int256",
              },
              {
                components: [
                  {
                    internalType: "int256",
                    name: "x3",
                    type: "int256",
                  },
                  {
                    components: [
                      {
                        internalType: "int256",
                        name: "x4",
                        type: "int256",
                      },
                    ],
                    internalType: "struct Storage.struct4",
                    name: "struct4",
                    type: "tuple",
                  },
                ],
                internalType: "struct Storage.struct3",
                name: "struct3",
                type: "tuple",
              },
            ],
            internalType: "struct Storage.struct2",
            name: "struct2",
            type: "tuple",
          },
        ],
        internalType: "struct Storage.struct1",
        name: "",
        type: "tuple",
      },
    ],
    stateMutability: "view",
    type: "function",
  },
  {
    inputs: [
      {
        internalType: "uint256",
        name: "num",
        type: "uint256",
      },
      {
        components: [
          {
            internalType: "int256",
            name: "x1",
            type: "int256",
          },
          {
            components: [
              {
                internalType: "int256",
                name: "x2",
                type: "int256",
              },
              {
                components: [
                  {
                    internalType: "int256",
                    name: "x3",
                    type: "int256",
                  },
                  {
                    components: [
                      {
                        internalType: "int256",
                        name: "x4",
                        type: "int256",
                      },
                    ],
                    internalType: "struct Storage.struct4",
                    name: "struct4",
                    type: "tuple",
                  },
                ],
                internalType: "struct Storage.struct3",
                name: "struct3",
                type: "tuple",
              },
            ],
            internalType: "struct Storage.struct2",
            name: "struct2",
            type: "tuple",
          },
        ],
        internalType: "struct Storage.struct1",
        name: "name",
        type: "tuple",
      },
    ],
    name: "store",
    outputs: [],
    stateMutability: "nonpayable",
    type: "function",
  },
] as const;

const _bytecode =
  "0x608060405234801561001057600080fd5b506106b6806100206000396000f3fe608060405234801561001057600080fd5b50600436106100415760003560e01c80630e1819a9146100465780632e64cec1146100625780636e7abac414610080575b600080fd5b610060600480360381019061005b91906104b8565b6100b0565b005b61006a610147565b6040516100779190610507565b60405180910390f35b61009a60048036038101906100959190610580565b610150565b6040516100a79190610665565b60405180910390f35b8160008190555080600160003373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff168152602001908152602001600020600082015181600001556020820151816001016000820151816000015560208201518160010160008201518160000155602082015181600101600082015181600001555050505050509050505050565b60008054905090565b61015861020d565b600160008373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020016000206040518060400160405290816000820154815260200160018201604051806040016040529081600082015481526020016001820160405180604001604052908160008201548152602001600182016040518060200160405290816000820154815250508152505081525050815250509050919050565b60405180604001604052806000815260200161022761022d565b81525090565b60405180604001604052806000815260200161024761024d565b81525090565b60405180604001604052806000815260200161026761026d565b81525090565b6040518060200160405280600081525090565b6000604051905090565b600080fd5b6000819050919050565b6102a28161028f565b81146102ad57600080fd5b50565b6000813590506102bf81610299565b92915050565b600080fd5b6000601f19601f8301169050919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052604160045260246000fd5b610313826102ca565b810181811067ffffffffffffffff82111715610332576103316102db565b5b80604052505050565b6000610345610280565b9050610351828261030a565b919050565b6000819050919050565b61036981610356565b811461037457600080fd5b50565b60008135905061038681610360565b92915050565b6000602082840312156103a2576103a16102c5565b5b6103ac602061033b565b905060006103bc84828501610377565b60008301525092915050565b6000604082840312156103de576103dd6102c5565b5b6103e8604061033b565b905060006103f884828501610377565b600083015250602061040c8482850161038c565b60208301525092915050565b60006060828403121561042e5761042d6102c5565b5b610438604061033b565b9050600061044884828501610377565b600083015250602061045c848285016103c8565b60208301525092915050565b60006080828403121561047e5761047d6102c5565b5b610488604061033b565b9050600061049884828501610377565b60008301525060206104ac84828501610418565b60208301525092915050565b60008060a083850312156104cf576104ce61028a565b5b60006104dd858286016102b0565b92505060206104ee85828601610468565b9150509250929050565b6105018161028f565b82525050565b600060208201905061051c60008301846104f8565b92915050565b600073ffffffffffffffffffffffffffffffffffffffff82169050919050565b600061054d82610522565b9050919050565b61055d81610542565b811461056857600080fd5b50565b60008135905061057a81610554565b92915050565b6000602082840312156105965761059561028a565b5b60006105a48482850161056b565b91505092915050565b6105b681610356565b82525050565b6020820160008201516105d260008501826105ad565b50505050565b6040820160008201516105ee60008501826105ad565b50602082015161060160208501826105bc565b50505050565b60608201600082015161061d60008501826105ad565b50602082015161063060208501826105d8565b50505050565b60808201600082015161064c60008501826105ad565b50602082015161065f6020850182610607565b50505050565b600060808201905061067a6000830184610636565b9291505056fea264697066735822122056a65683a56b2c1798df311e364dae51c18f76f5e3cf57634a5d39a4c7bdfad364736f6c63430008120033";

type StorageConstructorParams =
  | [signer?: Signer]
  | ConstructorParameters<typeof ContractFactory>;

const isSuperArgs = (
  xs: StorageConstructorParams
): xs is ConstructorParameters<typeof ContractFactory> => xs.length > 1;

export class Storage__factory extends ContractFactory {
  constructor(...args: StorageConstructorParams) {
    if (isSuperArgs(args)) {
      super(...args);
    } else {
      super(_abi, _bytecode, args[0]);
    }
  }

  override getDeployTransaction(
    overrides?: NonPayableOverrides & { from?: string }
  ): Promise<ContractDeployTransaction> {
    return super.getDeployTransaction(overrides || {});
  }
  override deploy(overrides?: NonPayableOverrides & { from?: string }) {
    return super.deploy(overrides || {}) as Promise<
      Storage & {
        deploymentTransaction(): ContractTransactionResponse;
      }
    >;
  }
  override connect(runner: ContractRunner | null): Storage__factory {
    return super.connect(runner) as Storage__factory;
  }

  static readonly bytecode = _bytecode;
  static readonly abi = _abi;
  static createInterface(): StorageInterface {
    return new Interface(_abi) as StorageInterface;
  }
  static connect(address: string, runner?: ContractRunner | null): Storage {
    return new Contract(address, _abi, runner) as unknown as Storage;
  }
}
