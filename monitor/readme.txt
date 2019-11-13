Zet execution policies voor het uitvoeren van Powershell scripts goed

Powershell


PS Get-ExecutionPolicy -List

                                                                          Scope                                                                 ExecutionPolicy
                                                                          -----                                                                 ---------------
                                                                  MachinePolicy                                                                       Undefined
                                                                     UserPolicy                                                                       Undefined
                                                                        Process                                                                       Undefined
                                                                    CurrentUser                                                                       Undefined
                                                                   LocalMachine                                                                       Undefined


PS Set-ExecutionPolicy

cmdlet Set-ExecutionPolicy at command pipeline position 1
Supply values for the following parameters:
ExecutionPolicy: Unrestricted
PS Get-ExecutionPolicy -List

                                                                          Scope                                                                 ExecutionPolicy
                                                                          -----                                                                 ---------------
                                                                  MachinePolicy                                                                       Undefined
                                                                     UserPolicy                                                                       Undefined
                                                                        Process                                                                       Undefined
                                                                    CurrentUser                                                                       Undefined
                                                                   LocalMachine                                                                    Unrestricted
